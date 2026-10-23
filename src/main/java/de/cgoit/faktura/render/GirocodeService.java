package de.cgoit.faktura.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import de.cgoit.faktura.render.RenderModel.Account;
import org.springframework.stereotype.Service;

/**
 * Creates the Girocode (EPC QR code, version 002, UTF-8) that lets a banking app prefill a SEPA credit transfer.
 */
@Service
public class GirocodeService {

    private static final int MAX_NAME_LENGTH = 70;
    private static final int MAX_REMITTANCE_LENGTH = 140;
    private static final int SIZE_PIXELS = 400;
    private static final int PLACEHOLDER_INSET = 20;
    private static final float PLACEHOLDER_STROKE = 6f;

    /**
     * Builds the EPC payload text. Fields are separated by line feeds as defined by the EPC069-12 guideline.
     *
     * @param amountCents  amount in euro cents, must be positive
     * @param remittance   unstructured remittance information, usually the invoice number
     */
    public String payload(Account account, long amountCents, String remittance) {
        var amount = BigDecimal.valueOf(amountCents, 2).toPlainString();

        return String.join("\n",
                "BCD",
                "002",
                "1",
                "SCT",
                clean(account.bic(), 11),
                clean(account.holder(), MAX_NAME_LENGTH),
                clean(account.iban() == null ? null : account.iban().replace(" ", ""), 34),
                "EUR" + amount,
                "",
                "",
                clean(remittance, MAX_REMITTANCE_LENGTH));
    }

    /**
     * Renders the Girocode as an opaque PNG (no transparency, which PDF/A-1 does not allow).
     */
    public byte[] png(Account account, long amountCents, String remittance) {
        try {
            var matrix = new QRCodeWriter().encode(payload(account, amountCents, remittance), BarcodeFormat.QR_CODE,
                    SIZE_PIXELS, SIZE_PIXELS,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 1));
            var image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);

            for (var x = 0; x < matrix.getWidth(); x++) {
                for (var y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }

            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);

            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("Girocode cannot be encoded", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Renders a neutral stand-in for the Girocode of a draft: a framed grey square that cannot be scanned. It keeps the
     * layout of the final document without suggesting a payment reference.
     */
    public byte[] placeholderPng() {
        var image = new BufferedImage(SIZE_PIXELS, SIZE_PIXELS, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();

        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, SIZE_PIXELS, SIZE_PIXELS);
        graphics.setColor(new Color(0xEEEEEE));
        graphics.fillRect(PLACEHOLDER_INSET, PLACEHOLDER_INSET, SIZE_PIXELS - 2 * PLACEHOLDER_INSET,
                SIZE_PIXELS - 2 * PLACEHOLDER_INSET);
        graphics.setColor(new Color(0xB0B0B0));
        graphics.setStroke(new BasicStroke(PLACEHOLDER_STROKE));
        graphics.drawRect(PLACEHOLDER_INSET, PLACEHOLDER_INSET, SIZE_PIXELS - 2 * PLACEHOLDER_INSET,
                SIZE_PIXELS - 2 * PLACEHOLDER_INSET);
        graphics.drawLine(PLACEHOLDER_INSET, PLACEHOLDER_INSET, SIZE_PIXELS - PLACEHOLDER_INSET,
                SIZE_PIXELS - PLACEHOLDER_INSET);
        graphics.drawLine(SIZE_PIXELS - PLACEHOLDER_INSET, PLACEHOLDER_INSET, PLACEHOLDER_INSET,
                SIZE_PIXELS - PLACEHOLDER_INSET);
        graphics.dispose();

        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);

            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        var text = value.replaceAll("[\\r\\n]+", " ").strip();

        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
