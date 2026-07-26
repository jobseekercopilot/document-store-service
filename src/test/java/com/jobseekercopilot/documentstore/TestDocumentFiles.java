package com.jobseekercopilot.documentstore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestDocumentFiles {

    private TestDocumentFiles() {}

    public static byte[] validPdf() {
        return "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
                .getBytes(StandardCharsets.ISO_8859_1);
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
