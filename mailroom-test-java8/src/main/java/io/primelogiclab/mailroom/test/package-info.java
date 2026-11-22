/**
 * Test helpers for code that sends email through mailroom. Neither transport talks to a real provider.
 *
 * <ul>
 *   <li>{@link io.primelogiclab.mailroom.test.InMemoryTransport} records every email and accepts it, with assertions
 *       such as "exactly one email to this address with this tag". Use it in application tests.</li>
 *   <li>{@link io.primelogiclab.mailroom.test.ScriptedTransport} does what its script says, one step per call:
 *       succeed, fail with a given failure kind, or wait first. Use it to test failover, retries and error
 *       handling.</li>
 * </ul>
 *
 * <p>Both implement {@link io.primelogiclab.mailroom.spi.MailTransport}, so they plug into a {@code Mailroom} like
 * any provider's transport. Assertions throw {@link java.lang.AssertionError}, so the helpers work with any test
 * framework, and this module depends on mailroom-core only.
 */
package io.primelogiclab.mailroom.test;
