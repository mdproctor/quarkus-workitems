package io.quarkiverse.workitems.dashboard;

/**
 * Entry point for the Quarkus WorkItems queue dashboard.
 *
 * <p>Usage: java -jar dashboard.jar [base-url]
 * <p>Default base URL: http://localhost:8080
 *
 * <p>Prerequisites: start the Quarkus application first, then run this dashboard.
 * <p>   cd quarkus-workitems-queues-examples && mvn quarkus:dev
 * <p>   cd dashboard && mvn exec:java
 */
public class Main {

    public static void main(final String[] args) throws Exception {
        final String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        System.out.println("Connecting to " + baseUrl + " ...");
        new QueueDashboard(new QueueDataClient(baseUrl)).run();
    }
}
