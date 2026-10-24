package de.cgoit.faktura.render;

import static de.cgoit.faktura.shared.Texts.blankToNull;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder.PdfAConformance;
import de.cgoit.faktura.render.internal.RenderProperties;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Mechanics shared by all letters (invoice, cancellation, dunning): the Thymeleaf engine for the templates in
 * {@code render/}, the stylesheet, the logo, the sender's return line and the PDF/A-1b output with embedded fonts.
 * The layout parts (letterhead, address, footer) live in {@code render/parts.xhtml}.
 */
final class RenderSupport {

    static final Locale LOCALE = Locale.GERMANY;

    private static final String DATE_PATTERN = "dd.MM.yyyy";
    private static final String PLACEHOLDER_LOGO = "render/logo-placeholder.png";
    private static final String FONT_FAMILY = "Invoice";

    private final MessageSource messages;
    private final RenderProperties properties;
    private final SpringTemplateEngine engine = new SpringTemplateEngine();

    RenderSupport(MessageSource messages, RenderProperties properties) {
        this.messages = messages;
        this.properties = properties;

        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("render/");
        resolver.setSuffix(".xhtml");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        engine.setTemplateResolver(resolver);
        engine.setMessageSource(messages);
        engine.setEnableSpringELCompiler(false);
    }

    RenderProperties properties() {
        return properties;
    }

    /**
     * Creates a context with the variables every letter needs: stylesheet, logo, side text and return line.
     */
    Context letterContext(RenderModel.Party sender) {
        var context = new Context(LOCALE);

        context.setVariable("css", css());
        context.setVariable("logo", logoDataUri());
        context.setVariable("sideText", blankToNull(properties.sideText()));
        context.setVariable("returnLine", returnLine(sender));

        return context;
    }

    String process(String template, Context context) {
        return engine.process(template, context);
    }

    /**
     * Renders XHTML to a PDF/A-1b document.
     */
    byte[] pdf(String html) {
        var out = new ByteArrayOutputStream();

        try {
            var builder = new PdfRendererBuilder();

            builder.usePdfAConformance(PdfAConformance.PDFA_1_B);
            builder.useColorProfile(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
            builder.useFont(() -> resource("fonts/DejaVuSans.ttf"), FONT_FAMILY, 400, FontStyle.NORMAL, true);
            builder.useFont(() -> resource("fonts/DejaVuSans-Bold.ttf"), FONT_FAMILY, 700, FontStyle.NORMAL, true);
            builder.useFont(() -> resource("fonts/DejaVuSans-Oblique.ttf"), FONT_FAMILY, 400, FontStyle.ITALIC, true);
            builder.useFont(() -> resource("fonts/DejaVuSans-BoldOblique.ttf"), FONT_FAMILY, 700, FontStyle.ITALIC,
                    true);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF rendering failed", e);
        }

        return out.toByteArray();
    }

    String countryName(RenderModel.Party recipient, RenderModel.Party sender) {
        var country = recipient.country();

        if (country == null || country.isBlank() || country.equalsIgnoreCase(sender.country())) {
            return null;
        }

        return Locale.of("", country).getDisplayCountry(Locale.GERMAN);
    }

    String message(String key, Object... args) {
        return messages.getMessage(key, args, LOCALE);
    }

    private String css() {
        try (var stream = resource("render/invoice.css")) {
            var pageOf = "\"" + message("render.page") + " \" counter(page) \" " + message("render.pageOf")
                    + " \" counter(pages)";

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\"{{pageOf}}\"", pageOf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String logoDataUri() {
        try {
            var path = properties.logoPath();
            BufferedImage source;

            if (path == null || path.isBlank()) {
                try (var stream = resource(PLACEHOLDER_LOGO)) {
                    source = ImageIO.read(stream);
                }
            } else {
                try (var stream = Files.newInputStream(Path.of(path))) {
                    source = ImageIO.read(stream);
                }
            }

            if (source == null) {
                throw new IllegalStateException("Logo file is not a supported image");
            }

            // PDF/A-1 forbids transparency: flatten onto white and write an opaque PNG
            var flat = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = flat.createGraphics();

            graphics.drawImage(source, 0, 0, Color.WHITE, null);
            graphics.dispose();

            var out = new ByteArrayOutputStream();

            ImageIO.write(flat, "png", out);

            return dataUri("image/png", out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Logo cannot be read", e);
        }
    }

    private String returnLine(RenderModel.Party sender) {
        var parts = new ArrayList<String>();

        parts.add(sender.name());
        addIfPresent(parts, sender.street());
        addIfPresent(parts, join(" ", sender.zip(), sender.city()));

        return String.join(" – ", parts);
    }

    static String format(String pattern, Object... args) {
        return new MessageFormat(pattern, LOCALE).format(args);
    }

    static String date(LocalDate date) {
        return DateTimeFormatter.ofPattern(DATE_PATTERN).format(date);
    }

    static String dataUri(String type, byte[] content) {
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(content);
    }

    private static InputStream resource(String path) {
        try {
            return new ClassPathResource(path).getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing resource " + path, e);
        }
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value);
        }
    }

    private static String join(String separator, String... values) {
        var parts = new ArrayList<String>();

        for (var value : values) {
            addIfPresent(parts, value);
        }

        return String.join(separator, parts);
    }
}
