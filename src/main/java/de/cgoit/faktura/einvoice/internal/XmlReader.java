package de.cgoit.faktura.einvoice.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import de.cgoit.faktura.einvoice.EInvoiceException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Small helper to read XML without external entities and to navigate by local element names.
 */
final class XmlReader {

    private XmlReader() {
    }

    static Document parse(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();

            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new EInvoiceException("The XML cannot be read", e);
        }
    }

    /**
     * Returns the direct child elements with the given local name.
     */
    static List<Element> children(Node parent, String name) {
        var result = new ArrayList<Element>();

        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                result.add(element);
            }
        }

        return result;
    }

    /**
     * Returns the first direct child element with the given local name, or {@code null}.
     */
    static Element child(Node parent, String name) {
        var found = children(parent, name);

        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * Returns all descendant elements with the given local name, in document order.
     */
    static List<Element> descendants(Node parent, String name) {
        var nodes = parent instanceof Document document
                ? document.getElementsByTagNameNS("*", name)
                : ((Element) parent).getElementsByTagNameNS("*", name);
        var result = new ArrayList<Element>();

        for (var i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }

        return result;
    }
}
