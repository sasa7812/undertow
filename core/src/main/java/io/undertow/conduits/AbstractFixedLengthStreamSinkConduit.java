/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.conduits;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

import org.xnio.Buffers;
import org.xnio.channels.FixedLengthOverflowException;
import org.xnio.channels.FixedLengthUnderflowException;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.conduits.AbstractStreamSinkConduit;
import org.xnio.conduits.Conduits;
import org.xnio.conduits.StreamSinkConduit;

import static java.lang.Math.min;
import static org.xnio.Bits.allAreClear;
import static org.xnio.Bits.allAreSet;
import static org.xnio.Bits.anyAreSet;
import static org.xnio.Bits.longBitMask;

/**
 * A channel which writes a fixed amount of data.  A listener is called once the data has been written.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public abstract class AbstractFixedLengthStreamSinkConduit extends AbstractStreamSinkConduit<StreamSinkConduit> {
    private int config;

    private long state;

    private static final int CONF_FLAG_CONFIGURABLE = 1 << 0;
    private static final int CONF_FLAG_PASS_CLOSE = 1 << 1;

    private static final long FLAG_CLOSE_REQUESTED = 1L << 63L;
    private static final long FLAG_CLOSE_COMPLETE = 1L << 62L;
    private static final long FLAG_FINISHED_CALLED = 1L << 61L;
    private static final long MASK_COUNT = longBitMask(0, 60);

    /**
     * Construct a new instance.
     *
     * @param next           the next channel
     * @param contentLength  the content length
     * @param configurable   {@code true} if this instance should pass configuration to the next
     * @param propagateClose {@code true} if this instance should pass close to the next
     */
    public AbstractFixedLengthStreamSinkConduit(final StreamSinkConduit next, final long contentLength, final boolean configurable, final boolean propagateClose) {
        super(next);
        if (contentLength < 0L) {
            throw new IllegalArgumentException("Content length must be greater than or equal to zero");
        } else if (contentLength > MASK_COUNT) {
            throw new IllegalArgumentException("Content length is too long");
        }
        config = (configurable ? CONF_FLAG_CONFIGURABLE : 0) | (propagateClose ? CONF_FLAG_PASS_CLOSE : 0);
        this.state = contentLength;
    }

    protected void reset(long contentLength, boolean propagateClose) {
        this.state = contentLength;
        if(propagateClose) {
            config |= CONF_FLAG_PASS_CLOSE;
        } else {
            config &= ~CONF_FLAG_PASS_CLOSE;
        }
    }

    public int write(final ByteBuffer src) throws IOException {
        long val = state;
        final long remaining = val & MASK_COUNT;
        if (!src.hasRemaining()) {
            return 0;
        }
        if (allAreSet(val, FLAG_CLOSE_REQUESTED)) {
            throw new ClosedChannelException();
        }
        if (src.remaining() > remaining) {
            throw new FixedLengthOverflowException();
        }
        int res = 0;
        try {
            return res = next.write(src);
        } finally {
            exitWrite(val, (long) res);
        }
    }

    public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException {
        if (length == 0) {
            return 0L;
        } else if (length == 1) {
            return write(srcs[offset]);
        }
        long val = state;
        final long remaining = val & MASK_COUNT;
        if (allAreSet(val, FLAG_CLOSE_REQUESTED)) {
            throw new ClosedChannelException();
        }
        long toWrite = Buffers.remaining(srcs, offset, length);
        if (toWrite > remaining) {
            throw new FixedLengthOverflowException();
        }
        long res = 0L;
        try {
            return res = next.write(srcs, offset, length);
        } finally {
            exitWrite(val, res);
        }
    }

    @Override
    public long writeFinal(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return Conduits.writeFinalBasic(this, srcs, offset, length);
    }

    @Override
    public int writeFinal(ByteBuffer src) throws IOException {
        return Conduits.writeFinalBasic(this, src);
    }

    public long transferFrom(final FileChannel src, final long position, final long count) throws IOException {
        if (count == 0L) return 0L;
        long val = state;
        if (allAreSet(val, FLAG_CLOSE_REQUESTED)) {
            throw new ClosedChannelException();
        }
        if (allAreClear(val, MASK_COUNT)) {
            throw new FixedLengthOverflowException();
        }
        long res = 0L;
        try {
            return res = next.transferFrom(src, position, min(count, (val & MASK_COUNT)));
        } finally {
            exitWrite(val, res);
        }
    }

    public long transferFrom(final StreamSourceChannel source, final long count, final ByteBuffer throughBuffer) throws IOException {
        if (count == 0L) return 0L;
        long val = state;
        if (allAreSet(val, FLAG_CLOSE_REQUESTED)) {
            throw new ClosedChannelException();
        }
        if (allAreClear(val, MASK_COUNT)) {
            throw new FixedLengthOverflowException();
        }
        long res = 0L;
        try {
            return res = next.transferFrom(source, min(count, (val & MASK_COUNT)), throughBuffer);
        } finally {
            exitWrite(val, res);
        }
    }

    public boolean flush() throws IOException {
        long val = state;
        if (anyAreSet(val, FLAG_CLOSE_COMPLETE)) {
            return true;
        }
        boolean flushed = false;
        try {
            return flushed = next.flush();
        } finally {
            exitFlush(val, flushed);
        }
    }

    public boolean isWriteResumed() {
        // not perfect but not provably wrong either...
        return allAreClear(state, FLAG_CLOSE_COMPLETE) && next.isWriteResumed();
    }

    public void wakeupWrites() {
        long val = state;
        if (anyAreSet(val, FLAG_CLOSE_COMPLETE)) {
            return;
        }
        next.wakeupWrites();
    }

    public void terminateWrites() throws IOException {
        final long val = enterShutdown();
        if (anyAreSet(val, MASK_COUNT)) {
            try {
                throw new FixedLengthUnderflowException((val & MASK_COUNT) + " bytes remaining");
            } finally {
                next.truncateWrites();
            }
        } else if (allAreSet(config, CONF_FLAG_PASS_CLOSE)) {
            next.terminateWrites();
        }

    }

    public void awaitWritable() throws IOException {
        next.awaitWritable();
    }

    public void awaitWritable(final long time, final TimeUnit timeUnit) throws IOException {
        next.awaitWritable(time, timeUnit);
    }


    /**
     * Get the number of remaining bytes in this fixed length channel.
     *
     * @return the number of remaining bytes
     */
    public long getRemaining() {
        return state & MASK_COUNT;
    }

    private void exitWrite(long oldVal, long consumed) {
        long newVal = oldVal - consumed;
        state = newVal;
    }

    private void exitFlush(long oldVal, boolean flushed) {
        long newVal = oldVal;
        boolean callFinish = false;
        if ((anyAreSet(oldVal, FLAG_CLOSE_REQUESTED) || (newVal & MASK_COUNT) == 0L) && flushed) {
            newVal |= FLAG_CLOSE_COMPLETE;

            if (!anyAreSet(oldVal, FLAG_FINISHED_CALLED) && (newVal & MASK_COUNT) == 0L) {
                newVal |= FLAG_FINISHED_CALLED;
                callFinish = true;
            }
            state = newVal;
            if (callFinish) {
                channelFinished();
            }
        }
    }

    protected void channelFinished() {
    }

    private long enterShutdown() {
        long oldVal, newVal;
        oldVal = state;
        if (anyAreSet(oldVal, FLAG_CLOSE_REQUESTED | FLAG_CLOSE_COMPLETE)) {
            // no action necessary
            return oldVal;
        }
        newVal = oldVal | FLAG_CLOSE_REQUESTED;
        if (anyAreSet(oldVal, MASK_COUNT)) {
            // error: channel not filled.  set both close flags.
            newVal |= FLAG_CLOSE_COMPLETE;
        }
        state = newVal;
        return oldVal;
    }

}
