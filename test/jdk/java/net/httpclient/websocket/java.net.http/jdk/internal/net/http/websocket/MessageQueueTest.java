/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class MessageQueueTest {

    private static final Random r = new SecureRandom();

    @DataProvider(name = "illegalCapacities")
    public static Object[][] illegalCapacities() {
        return new Object[][]{
                new Object[]{Integer.MIN_VALUE},
                new Object[]{-2},
                new Object[]{-1},
                new Object[]{ 0},
        };
    }

    @Test(dataProvider = "illegalCapacities")
    public void illegalCapacity(int n) {
        assertThrows(IllegalArgumentException.class, () -> new MessageQueue(n));
    }

    @Test(dataProvider = "capacities")
    public void emptiness(int n) {
        assertTrue(new MessageQueue(n).isEmpty());
    }

    @Test(dataProvider = "capacities")
    public void fullness(int n) throws IOException {
        MessageQueue q = new MessageQueue(n);
        Adder adder = new Adder();
        Queue<Message> referenceQueue = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            Message m = createRandomMessage();
            referenceQueue.add(m);
            adder.add(q, m);
        }
        for (int i = 0; i < n + 1; i++) {
            Message m = createRandomMessage();
            assertThrows(IOException.class, () -> adder.add(q, m));
        }
        for (int i = 0; i < n; i++) {
            Message expected = referenceQueue.remove();
            Message actual = new Remover().removeFrom(q);
            assertEquals(actual, expected);
        }
    }

    private Message createRandomMessage() {
        Message.Type[] values = Message.Type.values();
        Message.Type type = values[r.nextInt(values.length)];
        ByteBuffer binary = null;
        CharBuffer text = null;
        boolean isLast = false;
        int statusCode = -1;
        switch (type) {
            case TEXT:
                text = CharBuffer.allocate(r.nextInt(17));
                isLast = r.nextBoolean();
                break;
            case BINARY:
                binary = ByteBuffer.allocate(r.nextInt(19));
                isLast = r.nextBoolean();
                break;
            case PING:
                binary = ByteBuffer.allocate(r.nextInt(19));
                break;
            case PONG:
                binary = ByteBuffer.allocate(r.nextInt(19));
                break;
            case CLOSE:
                text = CharBuffer.allocate(r.nextInt(17));
                statusCode = r.nextInt();
                break;
            default:
                throw new AssertionError();
        }
        BiConsumer<Integer, Throwable> action = new BiConsumer<>() {
            @Override
            public void accept(Integer o, Throwable throwable) { }
        };
        CompletableFuture<Integer> future = new CompletableFuture<>();
        return new Message(type, binary, text, isLast, statusCode, r.nextInt(),
                           action, future);
    }

    @Test(dataProvider = "capacities")
    public void caterpillarWalk(int n) throws IOException {
//        System.out.println("n: " + n);
        for (int p = 1; p <= n; p++) { // pace
//            System.out.println("  pace: " + p);
            MessageQueue q = new MessageQueue(n);
            Queue<Message> referenceQueue = new LinkedList<>();
            Adder adder = new Adder();
            for (int k = 0; k < (n / p) + 1; k++) {
//                System.out.println("    cycle: " + k);
                for (int i = 0; i < p; i++) {
                    Message m = createRandomMessage();
                    referenceQueue.add(m);
                    adder.add(q, m);
                }
                Remover remover = new Remover();
                for (int i = 0; i < p; i++) {
                    Message expected = referenceQueue.remove();
                    Message actual = remover.removeFrom(q);
                    assertEquals(actual, expected);
                }
                assertTrue(q.isEmpty());
            }
        }
    }

    /* Exercises only concurrent additions */
    @Test
    public void halfConcurrency() throws ExecutionException, InterruptedException {
        int n = Runtime.getRuntime().availableProcessors() + 2;
        ExecutorService executorService = Executors.newFixedThreadPool(n);
        CyclicBarrier start = new CyclicBarrier(n);
        Adder adder = new Adder();
        List<Future<?>> futures = new ArrayList<>(n);
        try {
            for (int k = 0; k < 1024; k++) {
                MessageQueue q = new MessageQueue(n);
                for (int i = 0; i < n; i++) {
                    Message m = createRandomMessage();
                    Future<Void> f = executorService.submit(() -> {
                        start.await();
                        adder.add(q, m);
                        return null;
                    });
                    futures.add(f);
                }
                for (Future<?> f : futures) {
                    f.get();    // Just to check for exceptions
                }
                futures.clear();
                // Make sure the queue is full
                assertThrows(IOException.class,
                             () -> adder.add(q, createRandomMessage()));
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    // TODO: same message; different messages; a mix thereof

    @Test
    public void concurrency() throws ExecutionException, InterruptedException {
        int nProducers = Runtime.getRuntime().availableProcessors() + 2;
        int nThreads = nProducers + 1;
        ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
        CyclicBarrier start = new CyclicBarrier(nThreads);
        MessageQueue q = new MessageQueue(nProducers);
        Adder adder = new Adder();
        Remover remover = new Remover();
        List<Message> expectedList = new ArrayList<>(nProducers);
        List<Message> actualList = new ArrayList<>(nProducers);
        List<Future<?>> futures = new ArrayList<>(nProducers);
        try {
            for (int k = 0; k < 1024; k++) {
                for (int i = 0; i < nProducers; i++) {
                    Message m = createRandomMessage();
                    expectedList.add(m);
                    Future<Void> f = executorService.submit(() -> {
                        start.await();
                        adder.add(q, m);
                        return null;
                    });
                    futures.add(f);
                }
                Future<Void> consumer = executorService.submit(() -> {
                    int i = 0;
                    start.await();
                    while (i < nProducers) {
                        Message m = remover.removeFrom(q);
                        if (m != null) {
                            actualList.add(m);
                            i++;
                        }
                    }
                    return null;
                });
                for (Future<?> f : futures) {
                    f.get();    // Just to check for exceptions
                }
                consumer.get(); // Waiting for consumer to collect all the messages
                assertEquals(actualList.size(), expectedList.size());
                for (Message m : expectedList) {
                    assertTrue(actualList.remove(m));
                }
                assertTrue(actualList.isEmpty());
                assertTrue(q.isEmpty());
                expectedList.clear();
                futures.clear();
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test(dataProvider = "capacities")
    public void testSingleThreaded(int n) throws IOException {
        Queue<Message> referenceQueue = new LinkedList<>();
        MessageQueue q = new MessageQueue(n);
        Adder adder = new Adder();
        for (int i = 0; i < n; i++) {
            Message m = createRandomMessage();
            referenceQueue.add(m);
            adder.add(q, m);
        }
        for (int i = 0; i < n; i++) {
            Message expected = referenceQueue.remove();
            Message actual = new Remover().removeFrom(q);
            assertEquals(actual, expected);
        }
        assertTrue(q.isEmpty());
    }

    @DataProvider(name = "capacities")
    public Object[][] capacities() {
        return new Object[][]{
                new Object[]{  1},
                new Object[]{  2},
                new Object[]{  3},
                new Object[]{  4},
                new Object[]{  5},
                new Object[]{  6},
                new Object[]{  7},
                new Object[]{  8},
                new Object[]{  9},
                new Object[]{128},
                new Object[]{256},
        };
    }

    // -- auxiliary test infrastructure --

    static class Adder {

        @SuppressWarnings("unchecked")
        void add(MessageQueue q, Message m) throws IOException {
            switch (m.type) {
                case TEXT:
                    q.addText(m.text, m.isLast, m.attachment, m.action, m.future);
                    break;
                case BINARY:
                    q.addBinary(m.binary, m.isLast, m.attachment, m.action, m.future);
                    break;
                case PING:
                    q.addPing(m.binary, m.attachment, m.action, m.future);
                    break;
                case PONG:
                    q.addPong(m.binary, m.attachment, m.action, m.future);
                    break;
                case CLOSE:
                    q.addClose(m.statusCode, m.text, m.attachment, m.action, m.future);
                    break;
                default:
                    throw new InternalError();
            }
        }
    }

    static class Remover {

        Message removeFrom(MessageQueue q) {
            Message m = q.peek(new MessageQueue.QueueCallback<>() {

                boolean called;

                @Override
                public <T> Message onText(CharBuffer message,
                                          boolean isLast,
                                          T attachment,
                                          BiConsumer<? super T, ? super Throwable> action,
                                          CompletableFuture<? super T> future) {
                    assertFalse(called);
                    called = true;
                    return new Message(Message.Type.TEXT, null, message, isLast,
                                       -1, attachment, action, future);
                }

                @Override
                public <T> Message onBinary(ByteBuffer message,
                                            boolean isLast,
                                            T attachment,
                                            BiConsumer<? super T, ? super Throwable> action,
                                            CompletableFuture<? super T> future) {
                    assertFalse(called);
                    called = true;
                    return new Message(Message.Type.BINARY, message, null, isLast,
                                       -1, attachment, action, future);
                }

                @Override
                public <T> Message onPing(ByteBuffer message,
                                          T attachment,
                                          BiConsumer<? super T, ? super Throwable> action,
                                          CompletableFuture<? super T> future) {
                    assertFalse(called);
                    called = true;
                    return new Message(Message.Type.PING, message, null, false,
                                       -1, attachment, action, future);
                }

                @Override
                public <T> Message onPong(ByteBuffer message,
                                          T attachment,
                                          BiConsumer<? super T, ? super Throwable> action,
                                          CompletableFuture<? super T> future) {
                    assertFalse(called);
                    called = true;
                    return new Message(Message.Type.PONG, message, null, false,
                                       -1, attachment, action, future);
                }

                @Override
                public <T> Message onClose(int statusCode,
                                           CharBuffer reason,
                                           T attachment,
                                           BiConsumer<? super T, ? super Throwable> action,
                                           CompletableFuture<? super T> future) {
                    assertFalse(called);
                    called = true;
                    return new Message(Message.Type.CLOSE, null, reason, false,
                                       statusCode, attachment, action, future);
                }

                @Override
                public Message onEmpty() throws RuntimeException {
                    return null;
                }
            });
            if (m != null) {
                q.remove();
            }
            return m;
        }
    }

    static class Message {

        private final Type type;
        private final ByteBuffer binary;
        private final CharBuffer text;
        private final boolean isLast;
        private final int statusCode;
        private final Object attachment;
        @SuppressWarnings("rawtypes")
        private final BiConsumer action;
        @SuppressWarnings("rawtypes")
        private final CompletableFuture future;

        <T> Message(Type type,
                    ByteBuffer binary,
                    CharBuffer text,
                    boolean isLast,
                    int statusCode,
                    T attachment,
                    BiConsumer<? super T, ? super Throwable> action,
                    CompletableFuture<? super T> future) {
            this.type = type;
            this.binary = binary;
            this.text = text;
            this.isLast = isLast;
            this.statusCode = statusCode;
            this.attachment = attachment;
            this.action = action;
            this.future = future;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, binary, text, isLast, statusCode, attachment, action, future);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Message message = (Message) o;
            return isLast == message.isLast &&
                    statusCode == message.statusCode &&
                    type == message.type &&
                    Objects.equals(binary, message.binary) &&
                    Objects.equals(text, message.text) &&
                    Objects.equals(attachment, message.attachment) &&
                    Objects.equals(action, message.action) &&
                    Objects.equals(future, message.future);
        }

        enum Type {
            TEXT,
            BINARY,
            PING,
            PONG,
            CLOSE
        }
    }
}
