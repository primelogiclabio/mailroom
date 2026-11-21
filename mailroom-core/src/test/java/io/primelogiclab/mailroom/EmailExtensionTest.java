package io.primelogiclab.mailroom;

import static io.primelogiclab.mailroom.EmailTest.problemsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
import org.junit.jupiter.api.Test;

class EmailExtensionTest {

    /** A provider-style options class: essential and content-providing when a template is set. */
    static final class TemplateOptions implements EmailExtension {
        private final String templateId;

        TemplateOptions(String templateId) {
            this.templateId = templateId;
        }

        String templateId() {
            return templateId;
        }

        @Override
        public boolean essential() {
            return templateId != null;
        }

        @Override
        public boolean providesContent() {
            return templateId != null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TemplateOptions && Objects.equals(templateId, ((TemplateOptions) other).templateId);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(templateId);
        }
    }

    /** A second extension with the default (non-essential, no content) behaviour. */
    static final class TrackingOptions implements EmailExtension {
        @Override
        public boolean equals(Object other) {
            return other instanceof TrackingOptions;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static Email.Builder addressed() {
        return Email.builder().to("player@example.com");
    }

    @Test
    void roundTripsThroughAnEmail() {
        TemplateOptions template = new TemplateOptions("t-1");
        TrackingOptions tracking = new TrackingOptions();
        Email email = EmailTest.minimal().with(template).with(tracking).build();

        assertThat(email.extension(TemplateOptions.class)).containsSame(template);
        assertThat(email.extension(TrackingOptions.class)).containsSame(tracking);
        assertThat(email.extensions()).containsExactly(template, tracking);

        Email copy = email.toBuilder().build();
        assertThat(copy.extension(TemplateOptions.class)).containsSame(template);
        assertThat(copy).isEqualTo(email).hasSameHashCodeAs(email);
    }

    @Test
    void anAbsentExtensionIsEmpty() {
        assertThat(EmailTest.minimal().build().extension(TemplateOptions.class)).isEmpty();
        assertThat(EmailTest.minimal().with(new TrackingOptions()).build().extension(TemplateOptions.class)).isEmpty();
        assertThat(EmailTest.minimal().build().extensions()).isEmpty();
    }

    @Test
    void anotherInstanceOfTheSameClassReplacesTheFirst() {
        Email email = EmailTest.minimal().with(new TemplateOptions("t-1")).with(new TrackingOptions())
                .with(new TemplateOptions("t-2")).build();
        assertThat(email.extension(TemplateOptions.class).map(TemplateOptions::templateId)).contains("t-2");
        assertThat(email.extensions()).hasSize(2);
    }

    @Test
    void equalityIncludesExtensions() {
        Email plain = EmailTest.minimal().build();
        Email withTemplate = EmailTest.minimal().with(new TemplateOptions("t-1")).build();
        assertThat(withTemplate).isNotEqualTo(plain);
        assertThat(withTemplate).isEqualTo(EmailTest.minimal().with(new TemplateOptions("t-1")).build());
        assertThat(withTemplate).isNotEqualTo(EmailTest.minimal().with(new TemplateOptions("t-2")).build());
    }

    @Test
    void extensionsCannotBeChangedAfterBuild() {
        Email email = EmailTest.minimal().with(new TrackingOptions()).build();
        assertThatThrownBy(() -> email.extensions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toStringCountsExtensions() {
        assertThat(EmailTest.minimal().with(new TrackingOptions()).build().toString()).endsWith(", extensions=1]");
    }

    @Test
    void defaultsAreNotEssentialAndSupplyNoContent() {
        EmailExtension bare = new EmailExtension() { };
        assertThat(bare.essential()).isFalse();
        assertThat(bare.providesContent()).isFalse();
        assertThat(new TemplateOptions(null).providesContent()).isFalse();
        assertThat(new TemplateOptions("t-1").essential()).isTrue();
    }

    @Test
    void aContentProvidingExtensionMakesSubjectAndBodiesOptional() {
        Email email = addressed().with(new TemplateOptions("welcome")).build();
        assertThat(email.subject()).isEmpty();
        assertThat(email.text()).isEmpty();
        assertThat(email.html()).isEmpty();
    }

    @Test
    void givenSubjectAndBodiesAreStillCheckedWhenContentIsProvided() {
        TemplateOptions template = new TemplateOptions("welcome");
        assertThat(problemsOf(addressed().with(template).subject("Hi\r\nBcc: x@example.org")))
                .containsExactly("the subject must not contain line breaks or other control characters");
        assertThat(problemsOf(addressed().with(template).subject("  ")))
                .containsExactly("the subject must not be blank");
        assertThat(problemsOf(addressed().with(template).text("")))
                .containsExactly("the text body must not be empty");
        assertThat(problemsOf(addressed().with(template).html("")))
                .containsExactly("the HTML body must not be empty");
        assertThat(addressed().with(template).subject("Welcome!").build().subject()).contains("Welcome!");
    }

    @Test
    void withoutContentTheCore1RulesApply() {
        assertThat(problemsOf(addressed().with(new TrackingOptions()))).containsExactly(
                "a subject is required", "a text body, an HTML body or both are required");
        assertThat(problemsOf(addressed().with(new TemplateOptions(null)))).containsExactly(
                "a subject is required", "a text body, an HTML body or both are required");
    }

    @Test
    void recipientsAreStillRequiredWhenContentIsProvided() {
        assertThat(problemsOf(Email.builder().with(new TemplateOptions("welcome"))))
                .containsExactly("at least one recipient (to, cc or bcc) is required");
    }

    @Test
    void nullExtensionsAndTypesAreRejected() {
        assertThatThrownBy(() -> EmailTest.minimal().with(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("extension");
        assertThatThrownBy(() -> EmailTest.minimal().build().extension(null)).isInstanceOf(NullPointerException.class);
    }
}
