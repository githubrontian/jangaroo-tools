package net.jangaroo.jooc.mxml;

import net.jangaroo.jooc.JangarooParser;
import net.jangaroo.jooc.ast.ClassDeclaration;
import net.jangaroo.jooc.ast.CompilationUnit;
import net.jangaroo.jooc.backend.ApiModelGenerator;
import net.jangaroo.jooc.input.InputSource;
import net.jangaroo.jooc.json.JsonArray;
import net.jangaroo.jooc.json.JsonObject;
import net.jangaroo.jooc.model.AnnotationModel;
import net.jangaroo.jooc.model.AnnotationPropertyModel;
import net.jangaroo.jooc.model.ClassModel;
import net.jangaroo.jooc.model.CompilationUnitModel;
import net.jangaroo.jooc.model.FieldModel;
import net.jangaroo.jooc.model.MemberModel;
import net.jangaroo.jooc.model.MethodModel;
import net.jangaroo.jooc.model.ParamModel;
import net.jangaroo.jooc.model.PropertyModel;
import net.jangaroo.jooc.util.PreserveLineNumberHandler;
import net.jangaroo.utils.CompilerUtils;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class MxmlToModelParser {

  public static final String MXML_NAMED_CONSTRUCTOR_PARAMETER_NAMESPACE = "mxml:namedConstructorParameter";
  public static final String MXML_DECLARATIONS = "Declarations";
  public static final String MXML_SCRIPT = "Script";
  public static final String MXML_METADATA = "Metadata";
  public static final String MXML_ID_ATTRIBUTE = "id";
  public static final String MXML_DEFAULT_PROPERTY_ANNOTATION = "DefaultProperty";
  public static final String RESOURCE_MANAGER_QNAME = "mx.resources.ResourceManager";
  public static final Pattern AT_RESOURCE_PATTERN = Pattern.compile("^\\s*@Resource\\s*\\(\\s*bundle\\s*=\\s*['\"]([a-zA-Z0-9_$]+)['\"]\\s*,\\s*key\\s*=\\s*['\"]([a-zA-Z0-9_$]+)['\"]\\s*\\)\\s*$");
  public static final String RESOURCE_ACCESS_CODE = "{%s.getInstance().getString(\"%s\",\"%s\")}";
  private final JangarooParser jangarooParser;

  private CompilationUnitModel compilationUnitModel;
  private int methodIndex;
  private StringBuilder code;

  public MxmlToModelParser(JangarooParser jangarooParser) {
    this.jangarooParser = jangarooParser;
  }

  /**
   * Parses the MXML file into a CompilationUnitModel
   * @param in the input source to parse
   * @return the parsed model
   * @throws java.io.IOException if the input stream could not be read
   * @throws org.xml.sax.SAXException if the XML was not well-formed
   */
  public CompilationUnitModel parse(InputSource in) throws IOException, SAXException {
    String qName = CompilerUtils.qNameFromRelativPath(in.getRelativePath());
    compilationUnitModel = new CompilationUnitModel(CompilerUtils.packageName(qName),
            new ClassModel(CompilerUtils.className(qName)));
    methodIndex = 0;
    code = new StringBuilder();

    BufferedInputStream inputStream = null;
    try {
      inputStream = new BufferedInputStream(in.getInputStream());
      parse(inputStream);
    } finally {
      if (inputStream != null) {
        inputStream.close();
      }
    }

    return compilationUnitModel;
  }

  /**
   * Parse the input stream content into a model.
   * Close the input stream after reading.
   *
   * @param inputStream the input stream
   * @throws java.io.IOException  if the input stream could not be read
   * @throws org.xml.sax.SAXException if the XML was not well-formed
   */
  private void parse(InputStream inputStream) throws IOException, SAXException {
    Document document = buildDom(inputStream);
    Element objectNode = document.getDocumentElement();
    String superClassName = createClassNameFromNode(objectNode);

    if (superClassName.equals(compilationUnitModel.getQName())) {
      jangarooParser.getLog().error("Cyclic inheritance error: super class and this component are the same!. There is something wrong!"); // TODO: MXML file position!
    }
    ClassModel classModel = compilationUnitModel.getClassModel();
    classModel.setSuperclass(superClassName);
    compilationUnitModel.addImport(superClassName);

    MethodModel constructorModel = classModel.createConstructor();

    code.append("super(");
    createConfigObjectParameter(objectNode);
    code.append(");");

    createFields(objectNode);

    processAttributesAndChildNodes(objectNode, "this");

    constructorModel.setBody(code.toString());
  }

  private void createConfigObjectParameter(Element element) throws IOException {
    CompilationUnitModel type = getCompilationUnitModel(element);
    ClassModel elementClassModel = type == null ? null : type.getClassModel();
    NamedNodeMap attributes = element.getAttributes();
    JsonObject namedConstructorParameters = new JsonObject();
    for (int i = 0; i < attributes.getLength(); i++) {
      Attr attribute = (Attr) attributes.item(i);
      if (MXML_NAMED_CONSTRUCTOR_PARAMETER_NAMESPACE.equals(attribute.getNamespaceURI())) {
        String propertyName = attribute.getLocalName();
        MemberModel propertyModel = elementClassModel == null ? null : findPropertyModel(elementClassModel, propertyName);
        Object propertyValue = getPropertyValue(propertyModel, attribute.getValue());
        namedConstructorParameters.set(propertyName, propertyValue);
      }
    }
    if (!namedConstructorParameters.isEmpty()) {
      code.append(namedConstructorParameters.toString(2, 4));
    }
  }

  private void createFields(Element objectNode) throws IOException {
    ClassModel classModel = compilationUnitModel.getClassModel();
    for (Element element : MxmlUtils.getChildElements(objectNode)) {
      if (MxmlUtils.isMxmlNamespace(element.getNamespaceURI())) {
        String elementName = element.getLocalName();
        if (MXML_DECLARATIONS.equals(elementName)) {
          for (Element declaration : MxmlUtils.getChildElements(element)) {
            String fieldName = declaration.getAttribute(MXML_ID_ATTRIBUTE);
            String type = createClassNameFromNode(declaration);
            PropertyModel fieldModel = new PropertyModel(fieldName, type);
            fieldModel.addGetter();
            fieldModel.addSetter();
            classModel.addMember(fieldModel);
            createPropertyAssignmentCode(declaration, "this", fieldModel);
          }
        } else if (MXML_SCRIPT.equals(elementName)) {
          classModel.addBodyCode(getTextContent(element));
        } else if (MXML_METADATA.equals(elementName)) {
          classModel.addAnnotationCode(getTextContent(element));
        } else {
          jangarooParser.getLog().error("Unknown MXML element: " + elementName);
        }
      }
    }
  }

  private ClassModel processAttributes(Element objectNode, CompilationUnitModel type, String variable) throws IOException {
    ClassModel classModel = type == null ? null : type.getClassModel();
    NamedNodeMap attributes = objectNode.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      Attr attribute = (Attr) attributes.item(i);
      String propertyName = attribute.getLocalName();
      if (attribute.getNamespaceURI() == null && !MXML_ID_ATTRIBUTE.equals(propertyName)) {
        String value = attribute.getValue();
        MemberModel propertyModel = null;
        if (classModel != null) {
          propertyModel = findPropertyModel(classModel, propertyName);
          if (propertyModel == null) {
            AnnotationModel eventModel = findEvent(classModel, propertyName);
            if (eventModel != null) {
              createEventHandlerCode(variable, value, eventModel);
              continue;
            }
          }
        }
        if (propertyModel == null) {
          propertyModel = createDynamicPropertyModel(classModel, propertyName);
        }
        createPropertyAssignmentCode(variable, propertyModel, value);
      }
    }
    return classModel;
  }

  private void processAttributesAndChildNodes(Element objectNode, String variable) throws IOException {
    CompilationUnitModel type = getCompilationUnitModel(objectNode);
    processAttributes(objectNode, type, variable);
    processChildNodes(objectNode, type, variable);
  }

  private void processChildNodes(Element objectNode, CompilationUnitModel type, String variable) throws IOException {
    ClassModel classModel = type == null ? null : type.getClassModel();
    List<Element> childNodes = MxmlUtils.getChildElements(objectNode);
    MemberModel defaultPropertyModel = findDefaultPropertyModel(classModel);
    List<Element> defaultPropertyValues = new ArrayList<Element>();
    for (Element element : childNodes) {
      if (!MxmlUtils.isMxmlNamespace(element.getNamespaceURI())) { // ignore MXML namespace; has been handled before.
        MemberModel propertyModel = null;
        if (objectNode.getNamespaceURI().equals(element.getNamespaceURI())) {
          if (classModel != null) {
            String name = element.getLocalName();
            propertyModel = findPropertyModel(classModel, name);
            if (propertyModel == null) {
              AnnotationModel eventModel = findEvent(classModel, name);
              if (eventModel != null) {
                String value = getTextContent(element);
                createEventHandlerCode(variable, value, eventModel);
                continue;
              }
            }
          }
        }
        if (propertyModel == null && defaultPropertyModel != null && createClassNameFromNode(element) != null) {
          // collect item to add it to the default property later:
          defaultPropertyValues.add(element);
        } else {
          if (propertyModel == null) {
            propertyModel = createDynamicPropertyModel(classModel, element.getLocalName());
          }
          List<Element> childElements = MxmlUtils.getChildElements(element);
          if (childElements.isEmpty()) {
            createPropertyAssignmentCode(variable, propertyModel, getTextContent(element));
          } else {
            createChildElementsPropertyAssignmentCode(childElements, variable, propertyModel);
          }
        }
      }
    }
    if (!defaultPropertyValues.isEmpty()) {
      createChildElementsPropertyAssignmentCode(defaultPropertyValues, variable, defaultPropertyModel);
    }
  }

  private void createChildElementsPropertyAssignmentCode(List<Element> childElements, String variable,
                                                         MemberModel propertyModel) throws IOException {
    List<String> arrayItems = new ArrayList<String>();
    for (Element arrayItemNode : childElements) {
      String arrayItemClassName = createClassNameFromNode(arrayItemNode);
      if (arrayItemClassName != null) {
        compilationUnitModel.addImport(arrayItemClassName);
        String auxVarName = "$$" + (++methodIndex);
        code.append("\n    var ").append(auxVarName).append(":").append(arrayItemClassName);
        String id = arrayItemNode.getAttribute(MXML_ID_ATTRIBUTE);
        if (id.length() > 0) {
          compilationUnitModel.getClassModel().addMember(new FieldModel(id, arrayItemClassName));
          code.append(" = this.").append(id);
        }
        code.append(" = ").append("new ").append(arrayItemClassName).append("(");
        createConfigObjectParameter(arrayItemNode);
        code.append(");");
        processAttributesAndChildNodes(arrayItemNode, auxVarName);
        arrayItems.add(CompilerUtils.createCodeExpression(auxVarName));
      }
    }
    String value;
    if (arrayItems.size() > 1 || "Array".equals(propertyModel.getType())) {
      // TODO: Check for type violation
      // We must create an array.
      value = CompilerUtils.createCodeExpression(new JsonArray(arrayItems.toArray()).toString());
    } else if (arrayItems.size() == 1) {
      // The property is either unspecified, untyped, or object-typed
      // and it contains at least one child element. Use the first element as the
      // property value.
      value = arrayItems.get(0);
    } else {
      jangarooParser.getLog().error("Non-array property must not have multiple MXML child elements."); // TODO: MXML file position!
      value = CompilerUtils.createCodeExpression("undefined");
    }
    createPropertyAssignmentCode(variable, propertyModel, value);
  }

  private void createEventHandlerCode(String variable, String value, AnnotationModel event) {
    AnnotationPropertyModel eventType = event.getPropertiesByName().get("type");
    String eventTypeStr = eventType == null ? "Object" : eventType.getStringValue();
    compilationUnitModel.addImport(eventTypeStr);
    String eventName = event.getPropertiesByName().get("name").getStringValue();
    if (eventName.startsWith("on")) {
      eventName = eventName.substring(2);
    }
    String eventHandlerName = "___on_" + eventName + (++methodIndex);
    MethodModel eventHandler = new MethodModel(eventHandlerName, "void",
            new ParamModel("event", eventTypeStr));
    eventHandler.setBody(value);
    compilationUnitModel.getClassModel().addMember(eventHandler);
    compilationUnitModel.addImport("joo.addEventListener");
    code.append("\n    ").append("joo.addEventListener(").append(variable)
            .append(", '").append(eventName).append("', ").append(eventTypeStr).append(", ")
            .append(eventHandlerName).append(");");
  }

  private void createPropertyAssignmentCode(Element propertyElement, String variable, MemberModel propertyModel) throws IOException {
    List<Element> childElements = MxmlUtils.getChildElements(propertyElement);
    if (childElements.isEmpty()) {
      createPropertyAssignmentCode(variable, propertyModel, getTextContent(propertyElement));
    } else {
      createChildElementsPropertyAssignmentCode(childElements, variable, propertyModel);
    }
  }

  private void createPropertyAssignmentCode(String variable, MemberModel propertyModel, String value) {
    String attributeValueAsString = MxmlUtils.valueToString(getPropertyValue(propertyModel, value));
    code.append("\n    ").append(variable).append(".").append(propertyModel.getName()).append(" = ")
            .append(attributeValueAsString).append(";");
  }

  // ======================================== auxiliary methods ========================================

  private CompilationUnitModel getCompilationUnitModel(String fullClassName) throws IOException {
    if (fullClassName == null) {
      return null;
    }
    CompilationUnit compilationUnit = jangarooParser.getCompilationsUnit(fullClassName);
    if (compilationUnit == null) {
      jangarooParser.getLog().error("Undefined type: " + fullClassName);
      return null;
    }
    return new ApiModelGenerator(false).generateModel(compilationUnit); // TODO: cache!
  }

  private CompilationUnitModel getCompilationUnitModel(Element element) throws IOException {
    return getCompilationUnitModel(createClassNameFromNode(element));
  }

  private MemberModel findPropertyModel(ClassModel classModel, String propertyName) throws IOException {
    for (ClassModel current = classModel; current != null; current = getSuperClassModel(current)) {
      MemberModel propertyModel = current.getMember(propertyName);
      if (propertyModel != null && (propertyModel.isField() || propertyModel.isProperty())) {
        return propertyModel;
      }
    }
    return null;
  }

  private AnnotationModel findEvent(ClassModel classModel, String propertyName) throws IOException {
    for (ClassModel current = classModel; current != null; current = getSuperClassModel(current)) {
      AnnotationModel eventModel = current.getEvent(propertyName);
      if (eventModel != null) {
        return eventModel;
      }
    }
    return null;
  }

  private MemberModel findDefaultPropertyModel(ClassModel classModel) throws IOException {
    for (ClassModel current = classModel; current != null; current = getSuperClassModel(current)) {
      MemberModel defaultPropertyModel = current.findPropertyWithAnnotation(false, MXML_DEFAULT_PROPERTY_ANNOTATION);
      if (defaultPropertyModel != null) {
        return defaultPropertyModel;
      }
    }
    return null;
  }

  private MemberModel createDynamicPropertyModel(ClassModel classModel, String name) {
    if (classModel != null && !classModel.isDynamic()) {
      // dynamic property of a non-dynamic class: error!
      jangarooParser.getLog().error("Unresolved property " + name + " of type " + classModel.getName());
    }
    return new FieldModel(name, "*");
  }

  private ClassModel getSuperClassModel(ClassModel classModel) throws IOException {
    String superclass = classModel.getSuperclass();
    if (superclass != null) {
      CompilationUnitModel superCompilationUnitModel = getCompilationUnitModel(superclass);
      if (superCompilationUnitModel != null && superCompilationUnitModel.getPrimaryDeclaration() instanceof ClassModel) {
        return superCompilationUnitModel.getClassModel();
      }
    }
    return null;
  }

  private Object getPropertyValue(MemberModel propertyModel, String value) {
    Matcher resourceBundleMatcher = AT_RESOURCE_PATTERN.matcher(value);
    if (resourceBundleMatcher.matches()) {
      String bundle = resourceBundleMatcher.group(1);
      String key = resourceBundleMatcher.group(2);
      value = String.format(RESOURCE_ACCESS_CODE, RESOURCE_MANAGER_QNAME, bundle, key);
      compilationUnitModel.addImport(RESOURCE_MANAGER_QNAME);
      MxmlUtils.addResourceBundleAnnotation(compilationUnitModel.getClassModel(), bundle);
    }
    return MxmlUtils.getAttributeValue(value, propertyModel == null ? null : propertyModel.getType());
  }

  private String createClassNameFromNode(Node objectNode) {
    String name = objectNode.getLocalName();
    String uri = objectNode.getNamespaceURI();
    if (uri != null) {
      String packageName = MxmlUtils.parsePackageFromNamespace(uri);
      if (packageName != null) {
        String qName = CompilerUtils.qName(packageName, name);
        CompilationUnit compilationsUnit = jangarooParser.getCompilationsUnit(qName);
        if (compilationsUnit != null && compilationsUnit.getPrimaryDeclaration() instanceof ClassDeclaration) {
          return qName;
        }
      }
    }
    return null;
  }

  private static String getTextContent(Element element) {
    return element.getChildNodes().getLength() == 1 && element.getFirstChild().getNodeType() == Node.TEXT_NODE ? ((Text) element.getFirstChild()).getData() : "";
  }

  private Document buildDom(InputStream inputStream) throws SAXException, IOException {
    SAXParser parser;
    final Document doc;
    try {
      final SAXParserFactory saxFactory = SAXParserFactory.newInstance();
      saxFactory.setNamespaceAware(true);
      parser = saxFactory.newSAXParser();
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      doc = factory.newDocumentBuilder().newDocument();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("a default dom builder should be provided", e);
    }
    PreserveLineNumberHandler handler = new PreserveLineNumberHandler(doc);
    parser.parse(inputStream, handler);
    return doc;
  }
}