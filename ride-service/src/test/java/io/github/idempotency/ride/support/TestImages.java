package io.github.idempotency.ride.support;

import org.testcontainers.utility.DockerImageName;

public final class TestImages {

    public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:17.12-alpine");

    public static final DockerImageName REDIS = DockerImageName.parse("redis:8.8.4-alpine");

    public static final DockerImageName KAFKA = DockerImageName.parse("apache/kafka:4.4.0");

    public static final DockerImageName STRIPE_MOCK = DockerImageName.parse("stripe/stripe-mock:v0.206.0");

    /**
     * The plain HTTP port of stripe-mock; 12112 serves HTTPS with a self-signed certificate.
     */
    public static final int STRIPE_MOCK_PORT = 12111;

    private TestImages() {}
}
