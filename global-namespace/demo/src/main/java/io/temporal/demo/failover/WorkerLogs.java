package io.temporal.demo.failover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Follows a Compose service's container logs through the Docker API, for the deck's log panel. Needs
 * the Docker socket mounted into the deck's container, so it only works in the local Compose setup.
 */
public class WorkerLogs {

    private static final Path SOCKET = Path.of("/var/run/docker.sock");
    private static final ObjectMapper json = new ObjectMapper();

    private final String project;

    /** {@code project} is the Compose project, so a second copy of the demo doesn't mix in its logs. */
    public WorkerLogs(String project) {
        this.project = project;
    }

    public static boolean available() {
        return Files.exists(SOCKET);
    }

    /** Calls {@code onLine} for every log line of {@code service} until {@code onLine} throws. */
    public void follow(String service, int tail, Consumer<String> onLine) throws IOException {
        String filters =
                json.writeValueAsString(
                        Map.of(
                                "label",
                                List.of(
                                        "com.docker.compose.project=" + project,
                                        "com.docker.compose.service=" + service)));
        JsonNode containers;
        try (InputStream in = get("/containers/json?filters=" + URLEncoder.encode(filters, StandardCharsets.UTF_8))) {
            containers = json.readTree(in);
        }
        if (containers.isEmpty()) {
            onLine.accept("(no running container for " + service + ")");
            return;
        }
        String id = containers.get(0).get("Id").asText();

        try (InputStream in = get("/containers/" + id + "/logs?follow=1&stdout=1&stderr=1&tail=" + tail)) {
            // Without a TTY, Docker multiplexes stdout and stderr into frames: an 8-byte header whose
            // last 4 bytes are the payload length, then the payload.
            byte[] header = new byte[8];
            StringBuilder partial = new StringBuilder();
            while (in.readNBytes(header, 0, 8) == 8) {
                int length = ByteBuffer.wrap(header, 4, 4).getInt();
                partial.append(new String(in.readNBytes(length), StandardCharsets.UTF_8));
                int newline;
                while ((newline = partial.indexOf("\n")) >= 0) {
                    onLine.accept(partial.substring(0, newline));
                    partial.delete(0, newline + 1);
                }
            }
        }
    }

    /**
     * Sends a GET over the socket and returns the body. HTTP/1.0 makes Docker close the connection at
     * the end of the body instead of using chunked encoding, so the body is simply the rest of the stream.
     */
    private InputStream get(String path) throws IOException {
        SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(SOCKET));
        channel.write(
                ByteBuffer.wrap(
                        ("GET " + path + " HTTP/1.0\r\nHost: docker\r\n\r\n").getBytes(StandardCharsets.US_ASCII)));
        InputStream in = Channels.newInputStream(channel);
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Docker API closed the connection before the body");
            }
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        return in;
    }
}
