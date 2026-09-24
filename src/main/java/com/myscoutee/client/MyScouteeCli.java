package com.myscoutee.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class MyScouteeCli {
    private static final String CONFIG_ENV = "MYSCOUTEE_CONFIG";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MyScouteeCli() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (MyScouteeApiException exception) {
            System.err.println("API error (HTTP " + exception.statusCode() + "): " + exception.responseBody());
            System.exit(2);
        } catch (RuntimeException | IOException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "configure" -> configure(args);
            case "connect" -> printJson(client().connect());
            case "assets" -> createFile(args, true);
            case "events" -> createFile(args, false);
            case "event" -> {
                if (args.length != 2) throw new IllegalArgumentException("Usage: myscoutee-client event <external-id>");
                printJson(client().event(args[1]));
            }
            case "encounters" -> {
                if (args.length < 2 || args.length > 4) throw new IllegalArgumentException("Usage: myscoutee-client encounters <external-id> [offset] [limit]");
                printJson(client().encounters(args[1], args.length > 2 ? Integer.parseInt(args[2]) : 0, args.length > 3 ? Integer.parseInt(args[3]) : 1000));
            }
            case "group-invites" -> {
                if (args.length < 3) throw new IllegalArgumentException("Usage: myscoutee-client group-invites <group-id> <participant-id> ...");
                printJson(client().inviteGroupParticipants(args[1], java.util.Arrays.asList(args).subList(2, args.length)));
            }
            case "invites" -> {
                if (args.length < 3) throw new IllegalArgumentException("Usage: myscoutee-client invites <external-id> <participant-id> ...");
                printJson(client().inviteParticipants(args[1], java.util.Arrays.asList(args).subList(2, args.length)));
            }
            case "watch" -> watch(args);
            case "unwatch" -> printJson(client().unwatch());
            case "show-config" -> showConfig();
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void configure(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: myscoutee-client configure <base-url> <token>");
        }
        Path path = configPath();
        Properties current = Files.exists(path) ? readProperties(path) : new Properties();
        String clientId = current.getProperty("clientId", UUID.randomUUID().toString());
        MyScouteeClientConfig config = MyScouteeClientConfig.of(args[1], args[2], clientId);

        Properties next = new Properties();
        next.setProperty("baseUrl", config.baseUrl().toString());
        next.setProperty("token", config.token());
        next.setProperty("clientId", config.clientId().toString());
        writeProperties(path, next);

        MyScouteeClient.ConnectResponse response = new MyScouteeClient(config).connect();
        System.out.println("Connected client " + config.clientId() + ".");
        printJson(response);
    }

    private static void createFile(String[] args, boolean assets) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: myscoutee-client " + (assets ? "assets" : "events")
                            + " <request.json> [<item-id>=<image-file> ...]");
        }
        JsonNode request = JSON.readTree(Files.readString(Path.of(args[1])));
        Map<String, Path> images = new LinkedHashMap<>();
        for (int index = 2; index < args.length; index++) {
            int separator = args[index].indexOf('=');
            if (separator <= 0 || separator == args[index].length() - 1) {
                throw new IllegalArgumentException("Image must use <item-id>=<image-file>: " + args[index]);
            }
            String itemId = args[index].substring(0, separator);
            if (images.putIfAbsent(itemId, Path.of(args[index].substring(separator + 1))) != null) {
                throw new IllegalArgumentException("Only one image can be attached to an item id: " + itemId);
            }
        }
        MyScouteeClient api = client();
        JsonNode response = assets
                ? api.createAssets(request, images)
                : api.createEvents(request, images);
        printJson(response);
    }

    private static void watch(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: myscoutee-client watch <callback-url> [event.changed,asset.changed,event.encounters]");
        }
        List<String> events = args.length == 2
                ? List.of()
                : Arrays.stream(args[2].split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
        printJson(client().watch(args[1], events));
    }

    private static void showConfig() throws IOException {
        Properties properties = readProperties(configPath());
        System.out.println("baseUrl=" + properties.getProperty("baseUrl", ""));
        System.out.println("clientId=" + properties.getProperty("clientId", ""));
        String token = properties.getProperty("token", "");
        System.out.println("token=" + (token.length() <= 12 ? "***" : token.substring(0, 12) + "…"));
    }

    private static MyScouteeClient client() throws IOException {
        Properties properties = readProperties(configPath());
        return new MyScouteeClient(MyScouteeClientConfig.of(
                properties.getProperty("baseUrl"),
                properties.getProperty("token"),
                properties.getProperty("clientId")));
    }

    private static Properties readProperties(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Client is not configured. Run the configure command first.");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void writeProperties(Path path, Properties properties) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, "myscoutee-client-", ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "MyScoutee client configuration");
        }
        try {
            Files.setPosixFilePermissions(temporary, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms keep their platform-default file permissions.
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path configPath() {
        String override = System.getenv(CONFIG_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home"), ".myscoutee", "client.properties");
    }

    private static void printJson(Object value) throws IOException {
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static void printUsage() {
        System.out.println("""
                MyScoutee API client

                  configure <base-url> <token>
                  connect
                  assets <request.json> [<item-id>=<image-file> ...]
                  events <request.json> [<item-id>=<image-file> ...]
                  event <external-id>
                  encounters <external-id> [offset] [limit]
                  invites <external-id> <participant-id> ...
                  group-invites <group-id> <participant-id> ...
                  watch <callback-url> [event.changed,asset.changed,event.encounters]
                  unwatch
                  show-config
                """);
    }
}
