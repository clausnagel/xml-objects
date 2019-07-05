package org.xmlobjects;

import org.atteo.classindex.ClassFilter;
import org.atteo.classindex.ClassIndex;
import org.xmlobjects.annotation.XMLElement;
import org.xmlobjects.annotation.XMLElements;
import org.xmlobjects.builder.ObjectBuildException;
import org.xmlobjects.builder.ObjectBuilder;
import org.xmlobjects.serializer.ObjectSerializer;
import org.xmlobjects.stream.EventType;
import org.xmlobjects.stream.XMLReadException;
import org.xmlobjects.stream.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class XMLObjects {
    private final ConcurrentHashMap<String, Map<String, ObjectBuilder<?>>> builders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, ObjectSerializer<?>>> serializers = new ConcurrentHashMap<>();

    private XMLObjects() {
        // just to thwart instantiation
    }

    public static XMLObjects newInstance() throws XMLObjectsException {
        return newInstance(Thread.currentThread().getContextClassLoader());
    }

    public static XMLObjects newInstance(ClassLoader classLoader) throws XMLObjectsException {
        XMLObjects context = new XMLObjects();
        context.loadBuilders(classLoader);
        context.loadSerializers(classLoader);

        return context;
    }

    public XMLObjects registerBuilder(ObjectBuilder<?> builder, String namespaceURI, String localName) throws XMLObjectsException {
        registerBuilder(builder,namespaceURI,localName, false);
        return this;
    }

    public ObjectBuilder<?> getBuilder(String namespaceURI, String localName) {
        return builders.getOrDefault(namespaceURI, Collections.emptyMap()).get(localName);
    }

    public ObjectBuilder<?> getBuilder(String localName) {
        return getBuilder(XMLConstants.NULL_NS_URI, localName);
    }

    public ObjectBuilder<?> getBuilder(QName name) {
        return getBuilder(name.getNamespaceURI(), name.getLocalPart());
    }

    public XMLObjects registerSerializer(ObjectSerializer<?> serializer, Class<?> objectType, String namespaceURI) throws XMLObjectsException {
        registerSerializer(serializer,objectType,namespaceURI,false);
        return this;
    }

    public ObjectSerializer<?> getSerializer(Class<?> objectType, String namespaceURI) {
        return serializers.getOrDefault(objectType.getName(), Collections.emptyMap()).get(namespaceURI);
    }

    public ObjectSerializer<?> getSerializer(Class<?> objectType) {
        return getSerializer(objectType, XMLConstants.NULL_NS_URI);
    }

    @SuppressWarnings("unchecked")
    public <T> T fromXML(XMLReader reader, Class<T> type) throws ObjectBuildException {
        try {
            T object = null;
            int stopAt = 0;

            while (reader.hasNext()) {
                EventType event = reader.nextTag();

                if (event == EventType.START_ELEMENT) {
                    ObjectBuilder<?> builder = getBuilder(reader.getName());
                    if (builder != null && type.isAssignableFrom(getObjectType(builder))) {
                        stopAt = reader.getDepth() - 2;
                        object = reader.getObjectUsingBuilder((ObjectBuilder<T>) builder);
                    }
                }

                if (event == EventType.END_ELEMENT) {
                    if (reader.getDepth() == stopAt)
                        return object;
                    else if (reader.getDepth() < stopAt)
                        throw new ObjectBuildException("XML reader is in an illegal state: depth = " + reader.getDepth() +
                                " but expected depth = " + stopAt + ".");
                }
            }

            return object;
        } catch (XMLReadException e) {
            throw new ObjectBuildException("Failed to get XML content.", e);
        }
    }

    private void loadBuilders(ClassLoader classLoader) throws XMLObjectsException {
        for (Class<? extends ObjectBuilder> type : ClassFilter.only()
                .withoutModifiers(Modifier.ABSTRACT)
                .satisfying(c -> c.isAnnotationPresent(XMLElement.class) || c.isAnnotationPresent(XMLElements.class))
                .from(ClassIndex.getSubclasses(ObjectBuilder.class, classLoader))) {

            boolean isSetElement = type.isAnnotationPresent(XMLElement.class);
            boolean isSetElements = type.isAnnotationPresent(XMLElements.class);

            if (isSetElement && isSetElements)
                throw new XMLObjectsException("The builder " + type.getName() + " uses both @XMLElement and @XMLElements.");

            ObjectBuilder<?> builder;
            try {
                builder = type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new XMLObjectsException("The builder " + type.getName() + " lacks a default constructor.", e);
            }

            if (isSetElement) {
                XMLElement element = type.getAnnotation(XMLElement.class);
                registerBuilder(builder, element.namespaceURI(), element.name(), true);
            } else if (isSetElements) {
                XMLElements elements = type.getAnnotation(XMLElements.class);
                for (XMLElement element : elements.value())
                    registerBuilder(builder, element.namespaceURI(), element.name(), true);
            }
        }
    }

    private void loadSerializers(ClassLoader classLoader) throws XMLObjectsException {
        for (Class<? extends ObjectSerializer> type : ClassFilter.only()
                .withoutModifiers(Modifier.ABSTRACT)
                .satisfying(c -> c.isAnnotationPresent(XMLElement.class) || c.isAnnotationPresent(XMLElements.class))
                .from(ClassIndex.getSubclasses(ObjectSerializer.class, classLoader))) {

            boolean isSetElement = type.isAnnotationPresent(XMLElement.class);
            boolean isSetElements = type.isAnnotationPresent(XMLElements.class);

            if (isSetElement && isSetElements)
                throw new XMLObjectsException("The serializer " + type.getName() + " uses both @XMLElement and @XMLElements.");

            ObjectSerializer<?> serializer;
            try {
                serializer = type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new XMLObjectsException("The serializer " + type.getName() + " lacks a default constructor.", e);
            }

            Class<?> objectType = getObjectType(serializer);
            if (objectType == null)
                throw new XMLObjectsException("Failed to retrieve object type of serializer " + type.getName() + ".");

            if (isSetElement) {
                XMLElement element = type.getAnnotation(XMLElement.class);
                registerSerializer(serializer, objectType, element.namespaceURI(), true);
            } else if (isSetElements) {
                XMLElements elements = type.getAnnotation(XMLElements.class);
                for (XMLElement element : elements.value())
                    registerSerializer(serializer, objectType, element.namespaceURI(), true);
            }
        }
    }

    private void registerBuilder(ObjectBuilder<?> builder, String namespaceURI, String localName, boolean failOnDuplicates) throws XMLObjectsException {
        ObjectBuilder<?> current = builders.computeIfAbsent(namespaceURI, v -> new HashMap<>()).put(localName, builder);
        if (current != null && failOnDuplicates)
            throw new XMLObjectsException("Two builders are registered for the same XML element '" +
                    new QName(namespaceURI, localName) + "': " +
                    builder.getClass().getName() + " and " + current.getClass().getName() + ".");
    }

    private void registerSerializer(ObjectSerializer<?> serializer, Class<?> objectType, String namespaceURI, boolean failOnDuplicates) throws XMLObjectsException {
        ObjectSerializer<?> current = serializers.computeIfAbsent(objectType.getName(), v -> new HashMap<>()).put(namespaceURI, serializer);
        if (current != null && failOnDuplicates)
            throw new XMLObjectsException("Two serializers are registered for the same object type '" +
                    objectType.getName() + "': " +
                    serializer.getClass().getName() + " and " + current.getClass().getName() + ".");
    }

    private Class<?> getObjectType(ObjectBuilder<?> builder) {
        try {
            return builder.getClass().getMethod("createObject", QName.class).getReturnType();
        } catch (NoSuchMethodException e) {
            return Object.class;
        }
    }

    private static Class<?> getObjectType(ObjectSerializer<?> parent) throws XMLObjectsException {
        try {
            Class<?> clazz = parent.getClass();
            Class<?>  objectType = null;

            do {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals("createElement") && !method.isSynthetic()) {
                        Type[] parameters = method.getGenericParameterTypes();
                        if (parameters.length > 0
                                && parameters[0] instanceof Class<?>
                                && !Modifier.isAbstract(((Class<?>) parameters[0]).getModifiers())) {
                            objectType = (Class<?>) parameters[0];
                            break;
                        }
                    }
                }
            } while (objectType == null && (clazz = clazz.getSuperclass()) != null);

            return objectType;
        } catch (Exception e) {
            throw new XMLObjectsException("Failed to retrieve object type of serializer " + parent.getClass().getName() + ".", e);
        }
    }

}