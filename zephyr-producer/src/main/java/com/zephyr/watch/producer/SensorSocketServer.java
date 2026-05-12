package com.zephyr.watch.producer;

import com.zephyr.watch.common.constants.KafkaConfig;
import com.zephyr.watch.common.entity.SensorReading;
import com.zephyr.watch.common.utils.JsonUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Socket-to-Kafka gateway:
 * - TCP server on 9999
 * - Frames by '\n'
 * - Validates JSON as SensorReading
 * - Sends valid raw JSON to KafkaConfig.INPUT_TOPIC
 */
public final class SensorSocketServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SensorSocketServer.class.getName());
    private static final int DEFAULT_PORT = 9999;

    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_LINE_CHARS = 256 * 1024;
    private static final int MAX_PENDING_TASKS = 10_000;

    private final int port;
    private final KafkaProducer<String, String> producer;
    private final ThreadPoolExecutor workers;
    private final AtomicLong droppedTasks = new AtomicLong(0);
    private final AtomicLong invalidMessages = new AtomicLong(0);
    private final AtomicLong acceptedMessages = new AtomicLong(0);

    private volatile boolean running;
    private Selector selector;
    private ServerSocketChannel serverChannel;

    public SensorSocketServer(int port) {
        this.port = port;
        this.producer = createProducer();
        this.workers = createWorkers();
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args != null && args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (Exception ignored) {
                // keep default
            }
        }

        SensorSocketServer server = new SensorSocketServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }, "sensor-socket-server-shutdown"));

        server.start();
    }

    public void start() throws IOException {
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.serverChannel.bind(new InetSocketAddress(port));
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        this.running = true;

        LOG.info(() -> "SensorSocketServer listening on port " + port + ", forwarding to Kafka topic '" + KafkaConfig.INPUT_TOPIC + "'");

        while (running) {
            selector.select(500);
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) {
                    closeKeyQuietly(key);
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        onAccept(key);
                    } else if (key.isReadable()) {
                        onRead(key);
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "I/O error, closing connection: " + e.getMessage(), e);
                    closeKeyQuietly(key);
                }
            }
        }
    }

    private void onAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) {
            return;
        }
        client.configureBlocking(false);
        client.socket().setTcpNoDelay(true);
        client.socket().setKeepAlive(true);

        ClientState state = new ClientState();
        client.register(selector, SelectionKey.OP_READ, state);
        LOG.info(() -> "Accepted client " + safeRemote(client));
    }

    private void onRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();
        if (state == null) {
            state = new ClientState();
            key.attach(state);
        }

        int bytesRead = ch.read(state.readBuffer);
        if (bytesRead == -1) {
            LOG.info(() -> "Client closed " + safeRemote(ch));
            closeKeyQuietly(key);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        state.readBuffer.flip();
        decodeAndFrameLines(state, ch);
        state.readBuffer.compact();
    }

    private void decodeAndFrameLines(ClientState state, SocketChannel ch) throws IOException {
        while (true) {
            CoderResult cr = state.decoder.decode(state.readBuffer, state.charBuffer, false);
            state.charBuffer.flip();
            while (state.charBuffer.hasRemaining()) {
                char c = state.charBuffer.get();
                if (c == '\n') {
                    String line = state.lineBuffer.toString();
                    state.lineBuffer.setLength(0);
                    if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                        line = line.substring(0, line.length() - 1);
                    }
                    handleLine(line, ch);
                } else {
                    if (state.lineBuffer.length() >= MAX_LINE_CHARS) {
                        LOG.warning(() -> "Dropping oversized frame from " + safeRemote(ch) + " (>" + MAX_LINE_CHARS + " chars)");
                        state.lineBuffer.setLength(0);
                        invalidMessages.incrementAndGet();
                        drainUntilNewline(state);
                        return;
                    }
                    state.lineBuffer.append(c);
                }
            }
            state.charBuffer.clear();

            if (cr.isUnderflow()) {
                break;
            }
            if (cr.isOverflow()) {
                continue;
            }
            if (cr.isError()) {
                cr.throwException();
            }
        }
    }

    private void drainUntilNewline(ClientState state) {
        int i = state.readBuffer.position();
        int limit = state.readBuffer.limit();
        for (; i < limit; i++) {
            if (state.readBuffer.get(i) == (byte) '\n') {
                state.readBuffer.position(i + 1);
                return;
            }
        }
        state.readBuffer.position(limit);
    }

    private void handleLine(String raw, SocketChannel ch) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty()) {
            return;
        }

        try {
            workers.execute(() -> validateAndSend(line, ch));
        } catch (Exception rejected) {
            long dropped = droppedTasks.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                LOG.warning(() -> "Worker queue saturated, dropped_tasks=" + dropped);
            }
        }
    }

    private void validateAndSend(String json, SocketChannel ch) {
        try {
            SensorReading reading = JsonUtils.parseSensorReading(json);
            if (!isValid(reading)) {
                long invalid = invalidMessages.incrementAndGet();
                if (invalid == 1 || invalid % 1000 == 0) {
                    LOG.warning(() -> "Invalid sensor payloads dropped: " + invalid);
                }
                return;
            }

            String key = String.valueOf(reading.getMachineId());
            producer.send(new ProducerRecord<>(KafkaConfig.INPUT_TOPIC, key, json), (metadata, exception) -> {
                if (exception != null) {
                    LOG.log(Level.WARNING, "Kafka send failed: " + exception.getMessage(), exception);
                }
            });

            long accepted = acceptedMessages.incrementAndGet();
            if (accepted == 1 || accepted % 5000 == 0) {
                LOG.info(() -> "Forwarded messages=" + accepted + ", invalid=" + invalidMessages.get() + ", dropped_tasks=" + droppedTasks.get());
            }
        } catch (Exception ex) {
            long invalid = invalidMessages.incrementAndGet();
            if (invalid == 1 || invalid % 1000 == 0) {
                LOG.warning(() -> "JSON parse/validation error, dropped invalid=" + invalid + ", last_error=" + ex.getMessage());
            }
        }
    }

    private boolean isValid(SensorReading reading) {
        if (reading == null) {
            return false;
        }
        if (reading.getMachineId() == null) {
            return false;
        }
        Double pressure = reading.getPressure();
        Double temperature = reading.getTemperature();
        if (!inRange(pressure, 0.0, 5000.0)) {
            return false;
        }
        return inRange(temperature, -100.0, 5000.0);
    }

    private boolean inRange(Double v, double minInclusive, double maxInclusive) {
        if (v == null) {
            return false;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return false;
        }
        return v >= minInclusive && v <= maxInclusive;
    }

    private static KafkaProducer<String, String> createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaConfig.BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "10");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(32 * 1024));
        return new KafkaProducer<>(props);
    }

    private ThreadPoolExecutor createWorkers() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        int threads = Math.min(cores * 2, 32);
        ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(MAX_PENDING_TASKS);

        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong idx = new AtomicLong(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sensor-socket-worker-" + idx.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        RejectedExecutionHandler reject = (r, executor) -> {
            long dropped = droppedTasks.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                LOG.warning(() -> "Worker queue saturated, dropped_tasks=" + dropped);
            }
        };

        return new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                queue,
                tf,
                reject
        );
    }

    private static String safeRemote(SocketChannel ch) {
        try {
            return Objects.toString(ch.getRemoteAddress());
        } catch (Exception ignored) {
            return "<unknown>";
        }
    }

    private static void closeKeyQuietly(SelectionKey key) {
        if (key == null) {
            return;
        }
        try {
            key.cancel();
        } catch (Exception ignored) {
        }
        try {
            if (key.channel() != null) {
                key.channel().close();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        if (selector != null) {
            try {
                selector.wakeup();
            } catch (Exception ignored) {
            }
        }

        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (Exception ignored) {
        }

        try {
            workers.shutdown();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            workers.shutdownNow();
        }

        try {
            producer.flush();
        } catch (Exception ignored) {
        }
        try {
            producer.close();
        } catch (Exception ignored) {
        }

        LOG.info(() -> "SensorSocketServer stopped. forwarded=" + acceptedMessages.get()
                + ", invalid=" + invalidMessages.get()
                + ", dropped_tasks=" + droppedTasks.get());
    }

    private static final class ClientState {
        final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_BYTES);
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        final CharBuffer charBuffer = CharBuffer.allocate(READ_BUFFER_BYTES);
        final StringBuilder lineBuffer = new StringBuilder(1024);
    }
}

