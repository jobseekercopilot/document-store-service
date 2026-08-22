package com.jobseekercopilot.documentstore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

public final class TestDocumentFiles {

    private TestDocumentFiles() {}

    public static byte[] validPdf() {
        try (PDDocument document = textPdf()) {
            return save(document);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static byte[] pdfWithUriLinks(String... targets) {
        try (PDDocument document = textPdf()) {
            PDPage page = document.getPage(0);
            List<PDAnnotation> annotations = new ArrayList<>();
            for (int index = 0; index < targets.length; index++) {
                PDActionURI action = new PDActionURI();
                action.setURI(targets[index]);
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(72, 680 - (index * 20), 240, 16));
                link.setAction(action);
                annotations.add(link);
            }
            page.setAnnotations(annotations);
            return save(document);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static byte[] pdfWithBenignAaaaaaFontResource() {
        try (PDDocument document = textPdf()) {
            document.getPage(0).getResources().put(
                    COSName.getPDFName("AAAAAA+LiberationSans-Bold"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
            return save(document, CompressParameters.NO_COMPRESSION);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static byte[] imageOnlyPdf() {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PDDocument textPdf() throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText("Synthetic CV");
            content.endText();
        }
        return document;
    }

    private static byte[] save(PDDocument document) throws IOException {
        return save(document, CompressParameters.DEFAULT_COMPRESSION);
    }

    private static byte[] save(
            PDDocument document, CompressParameters compression) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.save(output, compression);
            return output.toByteArray();
        }
    }

    public static byte[] validDocx() {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip =
                        new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeEntry(
                    zip,
                    "[Content_Types].xml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml"
                        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            writeEntry(
                    zip,
                    "word/document.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Synthetic CV</w:t></w:r></w:p></w:body>
                    </w:document>
                    """);
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeEntry(
            ZipOutputStream zip,
            String name,
            String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
