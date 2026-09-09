package com.brook.tools.mediacompress;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ImageCompressor {
    public record Result(Path output, long inputSize, long outputSize) {
    }

    public Result compress(Path input, long maxBytes) throws IOException {
        long inputSize = Files.size(input);
        if (inputSize <= maxBytes) {
            throw new IllegalStateException("File is already ≤ " + AppConstants.formatMegabytes(maxBytes) + ".");
        }

        BufferedImage image = ImageIO.read(input.toFile());
        if (image == null) {
            throw new IOException("Unsupported or corrupt image file.");
        }

        float quality = 0.92f;
        double scale = 1.0;
        byte[] best = null;
        String format = chooseFormat(input);

        for (int attempt = 0; attempt < 20; attempt++) {
            BufferedImage scaled = scaleImage(image, scale);
            byte[] encoded = encode(scaled, format, quality);
            if (encoded.length <= maxBytes) {
                best = encoded;
                break;
            }
            if (quality > 0.35f) {
                quality -= 0.08f;
            } else {
                scale *= 0.85;
            }
        }

        if (best == null) {
            throw new IOException("Could not compress image below " + AppConstants.formatMegabytes(maxBytes) + ".");
        }

        String ext = format.equals("png") ? "png" : "jpg";
        Path output = outputPath(input, ext);
        Files.write(output, best);
        return new Result(output, inputSize, best.length);
    }

    private BufferedImage scaleImage(BufferedImage source, double scale) {
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        Image tmp = source.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.drawImage(tmp, 0, 0, null);
        g.dispose();
        return scaled;
    }

    private byte[] encode(BufferedImage image, String format, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IOException("No image writer for format: " + format);
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        try (var bos = new java.io.ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            writer.dispose();
            return bos.toByteArray();
        }
    }

    private String chooseFormat(Path input) {
        String name = input.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "jpeg";
        }
        return "jpeg";
    }

    private Path outputPath(Path input, String ext) {
        String base = stripExtension(input.getFileName().toString());
        Path parent = input.getParent() != null ? input.getParent() : Path.of(".");
        return parent.resolve(base + ".compressed." + ext);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
