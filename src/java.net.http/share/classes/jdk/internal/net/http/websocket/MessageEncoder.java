/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.net.http.websocket;

import jdk.internal.net.http.common.Utils;
import jdk.internal.net.http.websocket.Frame.Opcode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/*
 * A stateful producer of binary representations of WebSocket messages being
 * sent from the client to the server.
 *
 * An encoding methods are given original messages and byte buffers to put the
 * resulting bytes to.
 *
 * The method is called
 * repeatedly with a non-empty target buffer. Once the caller finds the buffer
 * unmodified after the call returns, the message has been completely encoded.
 */

/*
 * The state of encoding.An instance of this class is passed sequentially between messages, so
 * every message in a sequence can check the context it is in and update it
 * if necessary.
 */

public class MessageEncoder {

    // FIXME: write frame method

    private final static boolean DEBUG = false;

    private final SecureRandom maskingKeySource = new SecureRandom();
    private final Frame.HeaderWriter headerWriter = new Frame.HeaderWriter();
    private final Frame.Masker payloadMasker = new Frame.Masker();
    private final CharsetEncoder charsetEncoder
            = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    /*
     * This buffer is used both to encode characters to UTF-8 and to calculate
     * the length of the resulting frame's payload. The length of the payload
     * must be known before the frame's header can be written.
     * For implementation reasons, this buffer must have a capacity of at least
     * the maximum size of a Close frame payload, which is 125 bytes
     * (or Frame.MAX_CONTROL_FRAME_PAYLOAD_LENGTH).
     */
    private final ByteBuffer intermediateBuffer = createIntermediateBuffer(
            Frame.MAX_CONTROL_FRAME_PAYLOAD_LENGTH);
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(
            Frame.MAX_HEADER_SIZE_BYTES);

    private boolean started;
    private boolean flushing;
    private boolean moreText = true;
    private long headerCount;
    private boolean previousLast = true;
    private boolean previousText;
    private boolean closed;

    /*
     * How many bytes of the current message have been already encoded.
     *
     * Even though the user hands their buffers over to us, they still can
     * manipulate these buffers while we are getting data out of them.
     * The number of produced bytes guards us from such behaviour in the
     * case of messages that must be restricted in size (Ping, Pong and Close).
     * For other messages this measure provides a best-effort attempt to detect
     * concurrent changes to buffer.
     *
     * Making a shallow copy (duplicate/slice) and then checking the size
     * precondition on it would also solve the problem, but at the cost of this
     * extra copy.
     */
    private int actualLen;

    /*
     * How many bytes were originally there in the message, before the encoding
     * started.
     */
    private int expectedLen;

    /* Exposed for testing purposes */
    protected ByteBuffer createIntermediateBuffer(int minSize) {
        int capacity = Utils.getIntegerNetProperty(
                "jdk.httpclient.websocket.intermediateBufferSize", 16384);
        return ByteBuffer.allocate(Math.max(minSize, capacity));
    }

    public void reset() {
        // Do not reset the message stream state fields, e.g. previousLast,
        // previousText. Just an individual message state:
        started = false;
        flushing = false;
        moreText = true;
        headerCount = 0;
        actualLen = 0;
    }

    /*
     * Encodes text messages by cutting them into fragments of maximum size of
     * intermediateBuffer.capacity()
     */
    public boolean encodeText(CharBuffer src, boolean last, ByteBuffer dst)
            throws IOException
    {
        if (DEBUG) {
            System.out.printf("[Output] encodeText src.remaining()=%s, %s, %s%n",
                              src.remaining(), last, dst);
        }
        if (closed) {
            throw new IOException("Output closed");
        }
        if (!started) {
            if (!previousText && !previousLast) {
                // Previous data message was a partial binary message
                throw new IllegalStateException("Unexpected text message");
            }
            started = true;
            headerBuffer.position(0).limit(0);
            intermediateBuffer.position(0).limit(0);
            charsetEncoder.reset();
        }
        while (true) {
            if (DEBUG) {
                System.out.printf("[Output] put%n");
            }
            if (!putAvailable(headerBuffer, dst)) {
                return false;
            }
            if (DEBUG) {
                System.out.printf("[Output] mask%n");
            }
            if (maskAvailable(intermediateBuffer, dst) < 0) {
                return false;
            }
            if (DEBUG) {
                System.out.printf("[Output] moreText%n");
            }
            if (!moreText) {
                return true;
            }
            intermediateBuffer.clear();
            CoderResult r = null;
            if (!flushing) {
                r = charsetEncoder.encode(src, intermediateBuffer, true);
                if (r.isUnderflow()) {
                    flushing = true;
                }
            }
            if (flushing) {
                r = charsetEncoder.flush(intermediateBuffer);
                if (r.isUnderflow()) {
                    moreText = false;
                }
            }
            if (r.isError()) {
                try {
                    r.throwException();
                } catch (CharacterCodingException e) {
                    throw new IOException("Malformed text message", e);
                }
            }
            if (DEBUG) {
                System.out.printf("[Output] header #%s%n", headerCount);
            }
            if (headerCount == 0) { // set once
                previousLast = last;
                previousText = true;
            }
            intermediateBuffer.flip();
            headerBuffer.clear();
            int mask = maskingKeySource.nextInt();
            Opcode opcode = previousLast && headerCount == 0
                    ? Opcode.TEXT : Opcode.CONTINUATION;
            if (DEBUG) {
                System.out.printf("[Output] opcode %s%n", opcode);
            }
            headerWriter.fin(last && !moreText)
                    .opcode(opcode)
                    .payloadLen(intermediateBuffer.remaining())
                    .mask(mask)
                    .write(headerBuffer);
            headerBuffer.flip();
            headerCount++;
            payloadMasker.mask(mask);
        }
    }

    private boolean putAvailable(ByteBuffer src, ByteBuffer dst) {
        int available = dst.remaining();
        if (available >= src.remaining()) {
            dst.put(src);
            return true;
        } else {
            int lim = src.limit();                   // save the limit
            src.limit(src.position() + available);
            dst.put(src);
            src.limit(lim);                          // restore the limit
            return false;
        }
    }

    public boolean encodeBinary(ByteBuffer src, boolean last, ByteBuffer dst)
            throws IOException
    {
        if (DEBUG) {
            System.out.printf("[Output] encodeBinary %s, %s, %s%n",
                              src, last, dst);
        }
        if (closed) {
            throw new IOException("Output closed");
        }
        if (!started) {
            if (previousText && !previousLast) {
                // Previous data message was a partial text message
                throw new IllegalStateException("Unexpected binary message");
            }
            expectedLen = src.remaining();
            int mask = maskingKeySource.nextInt();
            headerBuffer.clear();
            headerWriter.fin(last)
                    .opcode(previousLast ? Opcode.BINARY : Opcode.CONTINUATION)
                    .payloadLen(expectedLen)
                    .mask(mask)
                    .write(headerBuffer);
            headerBuffer.flip();
            payloadMasker.mask(mask);
            previousLast = last;
            previousText = false;
            started = true;
        }
        if (!putAvailable(headerBuffer, dst)) {
            return false;
        }
        int count = maskAvailable(src, dst);
        actualLen += Math.abs(count);
        if (count >= 0 && actualLen != expectedLen) {
            throw new IOException("Concurrent message modification");
        }
        return count >= 0;
    }

    private int maskAvailable(ByteBuffer src, ByteBuffer dst) {
        int r0 = dst.remaining();
        payloadMasker.transferMasking(src, dst);
        int masked = r0 - dst.remaining();
        return src.hasRemaining() ? -masked : masked;
    }

    public boolean encodePing(ByteBuffer src, ByteBuffer dst)
            throws IOException
    {
        if (closed) {
            throw new IOException("Output closed");
        }
        if (DEBUG) System.out.printf("[Output] encodePing %s, %s%n", src, dst);
        if (!started) {
            expectedLen = src.remaining();
            if (expectedLen > Frame.MAX_CONTROL_FRAME_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("Long message: " + expectedLen);
            }
            int mask = maskingKeySource.nextInt();
            headerBuffer.clear();
            headerWriter.fin(true)
                    .opcode(Opcode.PING)
                    .payloadLen(expectedLen)
                    .mask(mask)
                    .write(headerBuffer);
            headerBuffer.flip();
            payloadMasker.mask(mask);
            started = true;
        }
        if (!putAvailable(headerBuffer, dst)) {
            return false;
        }
        int count = maskAvailable(src, dst);
        actualLen += Math.abs(count);
        if (count >= 0 && actualLen != expectedLen) {
            throw new IOException("Concurrent message modification");
        }
        return count >= 0;
    }

    public boolean encodePong(ByteBuffer src, ByteBuffer dst)
            throws IOException
    {
        if (closed) {
            throw new IOException("Output closed");
        }
        if (DEBUG) {
            System.out.printf("[Output] encodePong %s, %s%n",
                              src, dst);
        }
        if (!started) {
            expectedLen = src.remaining();
            if (expectedLen > Frame.MAX_CONTROL_FRAME_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("Long message: " + expectedLen);
            }
            int mask = maskingKeySource.nextInt();
            headerBuffer.clear();
            headerWriter.fin(true)
                    .opcode(Opcode.PONG)
                    .payloadLen(expectedLen)
                    .mask(mask)
                    .write(headerBuffer);
            headerBuffer.flip();
            payloadMasker.mask(mask);
            started = true;
        }
        if (!putAvailable(headerBuffer, dst)) {
            return false;
        }
        int count = maskAvailable(src, dst);
        actualLen += Math.abs(count);
        if (count >= 0 && actualLen != expectedLen) {
            throw new IOException("Concurrent message modification");
        }
        return count >= 0;
    }

    public boolean encodeClose(int statusCode, CharBuffer reason, ByteBuffer dst)
            throws IOException
    {
        if (DEBUG) {
            System.out.printf("[Output] encodeClose %s, reason.length=%s, %s%n",
                              statusCode, reason.length(), dst);
        }
        if (closed) {
            throw new IOException("Output closed");
        }
        if (!started) {
            if (DEBUG) {
                System.out.printf("[Output] reason size %s%n", reason.remaining());
            }
            intermediateBuffer.position(0).limit(Frame.MAX_CONTROL_FRAME_PAYLOAD_LENGTH);
            intermediateBuffer.putChar((char) statusCode);
            CoderResult r = charsetEncoder.reset().encode(reason, intermediateBuffer, true);
            if (r.isUnderflow()) {
                if (DEBUG) {
                    System.out.printf("[Output] flushing%n");
                }
                r = charsetEncoder.flush(intermediateBuffer);
            }
            if (DEBUG) {
                System.out.printf("[Output] encoding result: %s%n", r);
            }
            if (r.isError()) {
                try {
                    r.throwException();
                } catch (CharacterCodingException e) {
                    throw new IllegalArgumentException("Malformed reason", e);
                }
            } else if (r.isOverflow()) {
                // Here the 125 bytes size is ensured by the check for overflow
                throw new IllegalArgumentException("Long reason");
            } else if (!r.isUnderflow()) {
                throw new InternalError(); // assertion
            }
            intermediateBuffer.flip();
            headerBuffer.clear();
            int mask = maskingKeySource.nextInt();
            headerWriter.fin(true)
                    .opcode(Opcode.CLOSE)
                    .payloadLen(intermediateBuffer.remaining())
                    .mask(mask)
                    .write(headerBuffer);
            headerBuffer.flip();
            payloadMasker.mask(mask);
            started = true;
            closed = true;
            if (DEBUG) {
                System.out.printf("[Output] intermediateBuffer=%s%n",
                                  intermediateBuffer);
            }
        }
        if (!putAvailable(headerBuffer, dst)) {
            return false;
        }
        return maskAvailable(intermediateBuffer, dst) >= 0;
    }
}


