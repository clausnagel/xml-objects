package org.xmlobjects.stream;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xmlobjects.XMLObjects;
import org.xmlobjects.builder.ObjectBuildException;
import org.xmlobjects.builder.ObjectBuilder;
import org.xmlobjects.util.DepthXMLStreamReader;
import org.xmlobjects.util.Properties;
import org.xmlobjects.util.SAXWriter;
import org.xmlobjects.util.StAXMapper;
import org.xmlobjects.xml.Attributes;
import org.xmlobjects.xml.Namespaces;
import org.xmlobjects.xml.TextContent;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stax.StAXSource;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class XMLReader implements AutoCloseable {
    private final XMLObjects xmlObjects;
    private final DepthXMLStreamReader reader;
    private final boolean createDOMAsFallback;

    private final Map<String, ObjectBuilder<?>> builderCache = new HashMap<>();
    private final Properties properties = new Properties();
    private Transformer transformer;

    XMLReader(XMLObjects xmlObjects, XMLStreamReader reader, boolean createDOMAsFallback) {
        this.xmlObjects = Objects.requireNonNull(xmlObjects, "XML objects must not be null.");
        this.reader = new DepthXMLStreamReader(reader);
        this.createDOMAsFallback = createDOMAsFallback;
    }

    public XMLObjects getXMLObjects() {
        return xmlObjects;
    }

    public XMLStreamReader getStreamReader() {
        return reader;
    }

    public boolean isCreateDOMAsFallback() {
        return createDOMAsFallback;
    }

    public Namespaces getNamespaces() {
        return reader.getNamespaces();
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperty(String name, Object value) {
        properties.set(name, value);
    }

    @Override
    public void close() throws XMLReadException {
        try {
            builderCache.clear();
            reader.close();
        } catch (XMLStreamException e) {
            throw new XMLReadException("Caused by:", e);
        }
    }

    public int getDepth() {
        return reader.getDepth();
    }

    public boolean hasNext() throws XMLReadException {
        try {
            return reader.hasNext();
        } catch (XMLStreamException e) {
            throw new XMLReadException("Caused by:", e);
        }
    }

    public EventType nextTag() throws XMLReadException {
        try {
            do {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        return EventType.START_ELEMENT;
                    case XMLStreamConstants.END_ELEMENT:
                        return EventType.END_ELEMENT;
                }
            } while (reader.hasNext());

            return EventType.END_DOCUMENT;
        } catch (XMLStreamException e) {
            throw new XMLReadException("Caused by:", e);
        }
    }

    public QName getName() throws XMLReadException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT)
            throw new XMLReadException("Illegal to call getName when event is not START_ELEMENT.");

        return reader.getName();
    }

    public <T> T getObject(Class<T> type) throws ObjectBuildException, XMLReadException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT)
            throw new XMLReadException("Illegal to call getObject when event is not START_ELEMENT.");

        QName name = reader.getName();
        ObjectBuilder<T> builder = xmlObjects.getBuilder(name, type);
        if (builder != null) {
            T object = builder.createObject(name);
            if (object == null)
                throw new ObjectBuildException("The builder " + builder.getClass().getName() + " created a null value.");

            return getObject(object, name, builder);
        } else
            return null;
    }

    public <T> T getObjectUsingBuilder(Class<? extends ObjectBuilder<T>> type) throws ObjectBuildException, XMLReadException {
        return getObjectUsingBuilder(getOrCreateBuilder(type));
    }

    public <T> T getObjectUsingBuilder(ObjectBuilder<T> builder) throws ObjectBuildException, XMLReadException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT)
            throw new XMLReadException("Illegal to call getObjectUsingBuilder when event is not START_ELEMENT.");

        QName name = reader.getName();
        T object = builder.createObject(name);
        if (object == null)
            throw new ObjectBuildException("The builder " + builder.getClass().getName() + " created a null value.");

        return getObject(object, name, builder);
    }

    private <T> T getObject(T object, QName name, ObjectBuilder<T> builder) throws ObjectBuildException, XMLReadException {
        try {
            int stopAt = reader.getDepth() - 1;
            int childLevel = reader.getDepth() + 1;

            // initialize object
            builder.initializeObject(object, name, getAttributes(), this);

            while (true) {
                if (reader.getEventType() == XMLStreamConstants.START_ELEMENT && reader.getDepth() == childLevel) {
                    // build child object
                    builder.buildChildObject(object, reader.getName(), getAttributes(), this);
                }

                if (reader.getEventType() == XMLStreamConstants.END_ELEMENT) {
                    if (reader.getDepth() == stopAt)
                        return object;
                    else if (reader.getDepth() < stopAt) {
                        throw new XMLReadException("Reader is in illegal state (depth = " + stopAt +
                                " but expected depth = " + reader.getDepth() + ").");
                    }
                }

                if (reader.hasNext())
                    reader.next();
                else
                    return null;
            }
        } catch (XMLStreamException e) {
            throw new XMLReadException("Caused by:", e);
        }
    }

    public Element getDOMElement() throws XMLReadException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT)
            throw new XMLReadException("Illegal to call getObjectAsDOMElement when event is not START_ELEMENT.");

        try {
            if (transformer == null)
                transformer = TransformerFactory.newInstance().newTransformer();

            DOMResult result = new DOMResult();
            transformer.transform(new StAXSource(reader), result);
            Node node = result.getNode();
            transformer.reset();

            if (node.hasChildNodes()) {
                Node child = node.getFirstChild();
                if (child.getNodeType() == Node.ELEMENT_NODE)
                    return (Element) child;
            }

            return null;
        } catch (TransformerConfigurationException e) {
            throw new XMLReadException("Failed to initialize DOM transformer.", e);
        } catch (TransformerException e) {
            throw new XMLReadException("Failed to read XML content as DOM element.", e);
        }
    }

    public <T> BuildResult<T> getObjectOrDOMElement(Class<T> type) throws ObjectBuildException, XMLReadException {
        T object = getObject(type);
        if (object != null)
            return BuildResult.of(object);
        else if (createDOMAsFallback) {
            Element element = getDOMElement();
            if (element != null)
                return BuildResult.of(element);
        }

        return BuildResult.empty();
    }

    public Attributes getAttributes() throws XMLReadException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT)
            throw new XMLReadException("Illegal to call getAttributes when event is not START_ELEMENT.");

        Attributes attributes = new Attributes();
        for (int i = 0; i < reader.getAttributeCount(); i++)
            attributes.add(reader.getAttributeName(i), reader.getAttributeValue(i));

        return attributes;
    }

    public TextContent getTextContent() throws XMLReadException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT)
            throw new XMLReadException("Illegal to call getTextContent when event is not START_ELEMENT.");

        try {
            StringBuilder result = new StringBuilder();
            boolean shouldParse = true;

            while (shouldParse && reader.hasNext()) {
                int eventType = reader.next();
                switch (eventType) {
                    case XMLStreamReader.CHARACTERS:
                    case XMLStreamReader.CDATA:
                        result.append(reader.getText());
                        break;
                    case XMLStreamConstants.START_ELEMENT:
                    case XMLStreamReader.END_ELEMENT:
                        shouldParse = false;
                        break;
                }
            }

            return TextContent.of(result.toString());
        } catch (XMLStreamException e) {
            throw new XMLReadException("Caused by:", e);
        }
    }

    public String getMixedContent() throws XMLReadException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT)
            throw new XMLReadException("Illegal to call getMixedContent when event is not START_ELEMENT.");

        try (StringWriter writer = new StringWriter()) {
            try (SAXWriter saxWriter = new SAXWriter(writer)
                    .writeXMLDeclaration(false)
                    .writeEncoding(false)) {
                int stopAt = reader.getDepth() - 1;
                StAXMapper mapper = new StAXMapper(saxWriter);

                // map content of start element to a string representation
                while (reader.next() != XMLStreamConstants.END_ELEMENT || reader.getDepth() > stopAt)
                    mapper.mapEvent(reader);
            }

            return writer.toString();
        } catch (IOException | SAXException | XMLStreamException e) {
            throw new XMLReadException("Caused by:", e);
        }
    }

    public <T> ObjectBuilder<T> getOrCreateBuilder(Class<? extends ObjectBuilder<T>> type) throws ObjectBuildException {
        ObjectBuilder<T> builder;

        ObjectBuilder<?> cachedBuilder = builderCache.get(type.getName());
        if (cachedBuilder != null && type.isAssignableFrom(cachedBuilder.getClass()))
            builder = type.cast(cachedBuilder);
        else {
            try {
                builder = type.getDeclaredConstructor().newInstance();
                builderCache.put(type.getName(), builder);
            } catch (Exception e) {
                throw new ObjectBuildException("The builder " + type.getName() + " lacks a default constructor.");
            }
        }

        return builder;
    }
}
