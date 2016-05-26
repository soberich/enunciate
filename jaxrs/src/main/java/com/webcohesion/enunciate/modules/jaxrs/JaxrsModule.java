package com.webcohesion.enunciate.modules.jaxrs;

import com.webcohesion.enunciate.EnunciateContext;
import com.webcohesion.enunciate.EnunciateException;
import com.webcohesion.enunciate.api.ApiRegistry;
import com.webcohesion.enunciate.metadata.Ignore;
import com.webcohesion.enunciate.module.*;
import com.webcohesion.enunciate.modules.jaxrs.model.*;
import com.webcohesion.enunciate.util.IgnoreUtils;
import com.webcohesion.enunciate.util.PathSortStrategy;
import org.reflections.adapters.MetadataAdapter;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.Path;
import javax.ws.rs.ext.Provider;
import java.util.*;

import static com.webcohesion.enunciate.util.IgnoreUtils.isIgnored;

/**
 * @author Ryan Heaton
 */
@SuppressWarnings ( "unchecked" )
public class JaxrsModule extends BasicEnunicateModule implements TypeFilteringModule, ApiRegistryProviderModule, ApiFeatureProviderModule {

  private DataTypeDetectionStrategy defaultDataTypeDetectionStrategy;
  private final List<MediaTypeDefinitionModule> mediaTypeModules = new ArrayList<MediaTypeDefinitionModule>();
  private ApiRegistry apiRegistry;
  private EnunciateJaxrsContext jaxrsContext;
  static final String NAME = "jaxrs";
  private PathSortStrategy defaultSortStrategy = PathSortStrategy.breadth_first;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public List<DependencySpec> getDependencySpecifications() {
    return Arrays.asList((DependencySpec) new MediaTypeDependencySpec());
  }

  public DataTypeDetectionStrategy getDataTypeDetectionStrategy() {
    String dataTypeDetection = this.config.getString("[@datatype-detection]", null);

    if (dataTypeDetection != null) {
      try {
        return DataTypeDetectionStrategy.valueOf(dataTypeDetection);
      }
      catch (IllegalArgumentException e) {
        //fall through...
      }
    }

    if (this.defaultDataTypeDetectionStrategy != null) {
      return this.defaultDataTypeDetectionStrategy;
    }

    if (this.enunciate.getIncludePatterns().isEmpty()) {
      //if there are no configured include patterns, then we'll just stick with "local" detection so we don't include too much.
      return DataTypeDetectionStrategy.local;
    }
    else {
      //otherwise, we'll assume the user knows what (s)he's doing and aggressively include everything.
      return DataTypeDetectionStrategy.aggressive;
    }
  }

  public void setDefaultDataTypeDetectionStrategy(DataTypeDetectionStrategy strategy) {
    this.defaultDataTypeDetectionStrategy = strategy;
  }

  public PathSortStrategy getPathSortStrategy()  {
    PathSortStrategy strategy = defaultSortStrategy;
    try {
      strategy = PathSortStrategy.valueOf(this.config.getString("[@path-sort-strategy]", this.defaultSortStrategy.name()));
    } catch (IllegalArgumentException e) {
      // Ignore?  Log?
    }
    return strategy;
  }

  public void setDefaultSortStrategy(PathSortStrategy defaultSortStrategy) {
    this.defaultSortStrategy = defaultSortStrategy;
  }

  @Override
  public void setApiRegistry(ApiRegistry registry) {
    this.apiRegistry = registry;
  }

  public EnunciateJaxrsContext getJaxrsContext() {
    return jaxrsContext;
  }

  @Override
  public void call(EnunciateContext context) {
    jaxrsContext = new EnunciateJaxrsContext(context);

    DataTypeDetectionStrategy detectionStrategy = getDataTypeDetectionStrategy();
    String relativeContextPath = "";
    if (detectionStrategy != DataTypeDetectionStrategy.passive) {
      Set<? extends Element> elements = detectionStrategy == DataTypeDetectionStrategy.local ? context.getLocalApiElements() : context.getApiElements();
      for (Element declaration : elements) {
        if (declaration instanceof TypeElement) {
          TypeElement element = (TypeElement) declaration;

          if ("org.glassfish.jersey.server.wadl.internal.WadlResource".equals(element.getQualifiedName().toString())) {
            //known internal wadl resource not to be documented.
            continue;
          }

          if (isIgnored(declaration)) {
            continue;
          }

          Path pathInfo = declaration.getAnnotation(Path.class);
          if (pathInfo != null) {
            //add root resource.
            RootResource rootResource = new RootResource(element, jaxrsContext);
            jaxrsContext.add(rootResource);
            LinkedList<Element> contextStack = new LinkedList<Element>();
            contextStack.push(rootResource);
            try {
              for (ResourceMethod resourceMethod : rootResource.getResourceMethods(true)) {
                addReferencedDataTypeDefinitions(resourceMethod, contextStack);
              }
            }
            finally {
              contextStack.pop();
            }
          }

          Provider providerInfo = declaration.getAnnotation(Provider.class);
          if (providerInfo != null) {
            //add jax-rs provider
            jaxrsContext.addJAXRSProvider(element);
          }

          ApplicationPath applicationPathInfo = declaration.getAnnotation(ApplicationPath.class);
          if (applicationPathInfo != null) {
            relativeContextPath = applicationPathInfo.value();
          }
        }
      }
    }


    //tidy up the application path.
    relativeContextPath = this.config.getString("application[@path]", relativeContextPath);
    relativeContextPath = sanitizeContextPath(relativeContextPath);

    jaxrsContext.setRelativeContextPath(relativeContextPath);
    jaxrsContext.setGroupingStrategy(getGroupingStrategy());
    jaxrsContext.setPathSortStrategy(getPathSortStrategy());

    if (jaxrsContext.getRootResources().size() > 0) {
      this.enunciate.addArtifact(new JaxrsRootResourceClassListArtifact(this.jaxrsContext));
      this.apiRegistry.getResourceApis().add(jaxrsContext);
    }

    if (this.jaxrsContext.getProviders().size() > 0) {
      this.enunciate.addArtifact(new JaxrsProviderClassListArtifact(this.jaxrsContext));
    }
  }

  public static String sanitizeContextPath(String relativeContextPath) {
    while (relativeContextPath.startsWith("/")) {
      relativeContextPath = relativeContextPath.substring(1);
    }

    while (relativeContextPath.endsWith("/")) {
      //trim off any leading slashes
      relativeContextPath = relativeContextPath.substring(0, relativeContextPath.length() - 1);
    }

    return relativeContextPath;
  }

  /**
   * Add the referenced type definitions for the specified resource method.
   *
   * @param resourceMethod The resource method.
   */
  protected void addReferencedDataTypeDefinitions(ResourceMethod resourceMethod, LinkedList<Element> contextStack) {
    if (IgnoreUtils.isIgnored(resourceMethod)) {
      return;
    }

    ResourceEntityParameter ep = resourceMethod.getEntityParameter();
    if (ep != null) {
      Set<String> consumes = resourceMethod.getConsumesMediaTypes();
      contextStack.push(ep.getDelegate());

      TypeMirror type = ep.getType();
      contextStack.push(resourceMethod);
      try {
        for (MediaTypeDefinitionModule mediaTypeModule : this.mediaTypeModules) {
          mediaTypeModule.addDataTypeDefinitions(type, consumes, contextStack);
        }
      }
      finally {
        contextStack.pop();
      }
    }

    ResourceRepresentationMetadata outputPayload = resourceMethod.getRepresentationMetadata();
    if (outputPayload != null) {
      TypeMirror type = outputPayload.getDelegate();
      Set<String> produces = resourceMethod.getProducesMediaTypes();
      contextStack.push(resourceMethod);

      try {
        for (MediaTypeDefinitionModule mediaTypeModule : this.mediaTypeModules) {
          mediaTypeModule.addDataTypeDefinitions(type, produces, contextStack);
        }
      }
      finally {
        contextStack.pop();
      }
    }

    List<? extends ResponseCode> statusCodes = resourceMethod.getStatusCodes();
    if (statusCodes != null) {
      for (ResponseCode statusCode : statusCodes) {
        TypeMirror type = statusCode.getType();
        if (type != null) {
          Set<String> produces = resourceMethod.getProducesMediaTypes();
          contextStack.push(resourceMethod);

          try {
            for (MediaTypeDefinitionModule mediaTypeModule : this.mediaTypeModules) {
              mediaTypeModule.addDataTypeDefinitions(type, produces, contextStack);
            }
          }
          finally {
            contextStack.pop();
          }
        }
      }
    }
  }

  public EnunciateJaxrsContext.GroupingStrategy getGroupingStrategy() {
    String groupBy = this.config.getString("[@groupBy]", "class");
    if ("class".equals(groupBy)) {
      return EnunciateJaxrsContext.GroupingStrategy.resource_class;
    }
    else if ("path".equals(groupBy)) {
      return EnunciateJaxrsContext.GroupingStrategy.path;
    }
    else if ("annotation".equals(groupBy)) {
      return EnunciateJaxrsContext.GroupingStrategy.annotation;
    }
    else {
      throw new EnunciateException("Unknown grouping strategy: " + groupBy);
    }
  }

  @Override
  public boolean acceptType(Object type, MetadataAdapter metadata) {
    List<String> classAnnotations = metadata.getClassAnnotationNames(type);
    if (classAnnotations != null) {
      for (String classAnnotation : classAnnotations) {
        if ((Path.class.getName().equals(classAnnotation))
          || (Provider.class.getName().equals(classAnnotation))
          || (ApplicationPath.class.getName().equals(classAnnotation))) {
          return true;
        }
      }
    }
    return false;
  }

  public class MediaTypeDependencySpec implements DependencySpec {

    @Override
    public boolean accept(EnunciateModule module) {
      if (module instanceof MediaTypeDefinitionModule) {
        MediaTypeDefinitionModule definitionModule = (MediaTypeDefinitionModule) module;
        mediaTypeModules.add(definitionModule);

        // suggest to the media type definition module that it should take a passive approach to detecting data types
        // because this module will be aggressively adding the data type definitions to it.
        definitionModule.setDefaultDataTypeDetectionStrategy(DataTypeDetectionStrategy.passive);
        return true;
      }

      return false;
    }

    @Override
    public boolean isFulfilled() {
      // this spec is always fulfilled.
      return true;
    }

    @Override
    public String toString() {
      return "media type definition modules";
    }
  }
}
