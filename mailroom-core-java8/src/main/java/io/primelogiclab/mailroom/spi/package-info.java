/**
 * The provider SPI: what a transport module implements and declares. Applications don't need this package; provider
 * authors do.
 *
 * <ul>
 *   <li>{@link io.primelogiclab.mailroom.spi.MailTransport}: one configured way to send email;</li>
 *   <li>{@link io.primelogiclab.mailroom.spi.TransportProvider}: creates transports from configuration, found with
 *       {@link java.util.ServiceLoader};</li>
 *   <li>{@link io.primelogiclab.mailroom.spi.TransportConfig} and {@link io.primelogiclab.mailroom.spi.ConfigKey}: a
 *       transport's checked, typed properties;</li>
 *   <li>{@link io.primelogiclab.mailroom.spi.TransportCapabilities} and
 *       {@link io.primelogiclab.mailroom.spi.Capability}: what a transport can carry;</li>
 *   <li>{@link io.primelogiclab.mailroom.spi.SendContext}, {@link io.primelogiclab.mailroom.spi.Precheck} and
 *       {@link io.primelogiclab.mailroom.spi.BatchItemResult}: what a send gets and returns.</li>
 * </ul>
 *
 * <p>A transport also uses the public model: {@code Email}, {@code SendResult}, {@code SendFailedException},
 * {@code FailureKind} and {@code Secret}. Nothing in {@code io.primelogiclab.mailroom.internal} is part of the SPI.
 * The test {@code FakeProviderTest} in mailroom-core shows a complete provider written against this package alone.
 */
package io.primelogiclab.mailroom.spi;
