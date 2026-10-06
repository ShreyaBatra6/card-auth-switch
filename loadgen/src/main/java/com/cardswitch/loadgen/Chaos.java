package com.cardswitch.loadgen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Scheduled fault injection through the switch's admin API, e.g. take an issuer down 20s into a run and bring it
 * back at 40s, to watch stand-in processing and the circuit breaker under load.
 * <p>
 * Spec: {@code <second>:<issuer>:<key>=<value>[;<key>=<value>...]}, comma separated, for example
 * {@code 20:cedar:available=false,40:cedar:available=true} or {@code 30:harbor:medianLatencyMs=400;p99LatencyMs=900}.
 */
final class Chaos {

    record Event(int atSecond, String issuer, String json) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String adminUrl;
    private final List<Event> events;

    Chaos(String adminUrl, List<String> specs) {
        this.adminUrl = adminUrl;
        this.events = new ArrayList<>();
        for (String spec : specs) {
            String[] parts = spec.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Bad chaos spec '" + spec + "', expected <second>:<issuer>:<key>=<value>");
            }
            StringJoiner json = new StringJoiner(",", "{", "}");
            for (String pair : parts[2].split(";")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("Bad chaos setting '" + pair + "'");
                }
                json.add("\"" + kv[0].trim() + "\":" + kv[1].trim());
            }
            events.add(new Event(Integer.parseInt(parts[0].trim()), parts[1].trim(), json.toString()));
        }
    }

    List<Event> events() {
        return events;
    }

    void apply(Event event) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(adminUrl + "/admin/issuers/" + event.issuer() + "/simulator"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(event.json()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.printf("[chaos] t=%ds %s %s -> HTTP %d%n", event.atSecond(), event.issuer(), event.json(), response.statusCode());
        } catch (Exception e) {
            System.out.printf("[chaos] t=%ds %s failed: %s%n", event.atSecond(), event.issuer(), e);
        }
    }
}
