package net.jangaroo.exml.parser;

import net.jangaroo.exml.ExmlParseException;
import net.jangaroo.exml.generation.ExmlConfigClassGenerator;
import net.jangaroo.exml.model.ConfigClass;
import net.jangaroo.jooc.config.FileLocations;
import org.apache.commons.io.FilenameUtils;
import org.xml.sax.ContentHandler;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ExmlToConfigClassParser {
  public static ConfigClass generateConfigClass(File source, FileLocations locations, String configClassPackage) throws IOException {
    ConfigClass configClass = parseExmlToConfigClass(source, locations, configClassPackage);

    File targetPackageFolder = new File(locations.getOutputDirectory(), configClassPackage.replaceAll("\\.", File.separator));
    if(!targetPackageFolder.exists()) {
      targetPackageFolder.mkdirs();
    }

    File targetFile = getConfigClassOutputFileName(configClass, targetPackageFolder);

    // only recreate file if result file is older than the source file
    if(!targetFile.exists() || targetFile.lastModified() < source.lastModified()) {
      // generate the new config class ActionScript file
      ExmlConfigClassGenerator.generateClass(configClass, targetFile);
    }

    return configClass;
  }

  public static File getConfigClassOutputFileName(ConfigClass configClass, File targetPackageFolder) {
    return new File(targetPackageFolder, configClass.getName() + ".as");
  }

  private static ConfigClass parseExmlToConfigClass(File source, FileLocations locations, String configClassPackage) throws IOException {
    String fullQualifiedName = computeComponentFullQualifiedName(locations, source);
    ConfigClass configClass = new ConfigClass();
    configClass.setComponentName(fullQualifiedName);
    configClass.setPackageName(configClassPackage);
    configClass.setName(FilenameUtils.getBaseName(source.getName()));

    //read exml data and write it into the config class
    ExmlMetadataHandler metadataHandler = new ExmlMetadataHandler(configClass);
    parseFileWithHandler(source, metadataHandler);
    return configClass;
  }

  private static void parseFileWithHandler(File source, ContentHandler handler) {
    InputStream inputStream = null;
    try {
      inputStream = new FileInputStream(source);
      XMLReader xr = XMLReaderFactory.createXMLReader();
      xr.setContentHandler(handler);
      xr.parse(new org.xml.sax.InputSource(inputStream));
    } catch (ExmlParseException e) {
      // Simply pass our own exceptions.
      e.setSource(source);
      throw e;
    } catch (Exception e) {
      throw new ExmlParseException("could not parse EXML file", source, e);
    } finally {
      try {
        if (inputStream != null) {
          inputStream.close();
        }
      } catch (IOException e) {
        //never happened
      }
    }
  }

  private static String computeComponentFullQualifiedName(FileLocations locations, File sourceFile) throws IOException {
    File sourceDir = locations.findSourceDir(sourceFile);
    int rootDirPathLength = sourceDir.getPath().length()+1;
    String subpath = FilenameUtils.removeExtension(sourceFile.getPath().substring(rootDirPathLength));
    return subpath.replaceAll("\\" + File.separator, "\\.");
  }
}