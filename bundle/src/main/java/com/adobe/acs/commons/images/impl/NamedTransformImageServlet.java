/*
 * ACS AEM Commons
 *
 * Copyright (C) 2013 - 2023 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.images.impl;

import com.adobe.acs.commons.dam.RenditionPatternPicker;
import com.adobe.acs.commons.images.ImageTransformer;
import com.adobe.acs.commons.images.NamedImageTransformer;
import com.adobe.acs.commons.util.PathInfoUtil;
import com.day.cq.commons.DownloadResource;
import com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.commons.util.DamUtil;
import com.day.cq.dam.commons.util.OrientationUtil;
import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.foundation.Image;
import com.day.image.Layer;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.References;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.OptingServlet;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.commons.mime.MimeTypeService;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("serial")
@Component(
        label = "ACS AEM Commons - Named Transform Image Servlet",
        description = "Transform images programatically by applying a named transform to the requested Image.",
        metatype = true
)
@Properties({
        @Property(
                label = "Resource Types",
                description = "Resource Types and Node Types to bind this servlet to.",
                name = "sling.servlet.resourceTypes",
                value = { "nt/file", "nt/resource", "dam/Asset", "cq/Page", "cq/PageContent", "nt/unstructured",
                        "foundation/components/image", "foundation/components/parbase", "foundation/components/page" },
                propertyPrivate = false
        ),
        @Property(
            label = "Allows Suffix Patterns",
            description = "Regex pattern to filter allowed file names. Defaults to [ "
                    + NamedTransformImageServlet.DEFAULT_FILENAME_PATTERN + " ]",
            name = NamedTransformImageServlet.NAMED_IMAGE_FILENAME_PATTERN,
            value = NamedTransformImageServlet.DEFAULT_FILENAME_PATTERN
        ),
        @Property(
                label = "Extension",
                description = "",
                name = "sling.servlet.extensions",
                value = { "transform" },
                propertyPrivate = true
        ),
        @Property(
                name = "sling.servlet.methods",
                value = { "GET" },
                propertyPrivate = true
        )
})
@References({
        @Reference(
                name = "namedImageTransformers",
                referenceInterface = NamedImageTransformer.class,
                policy = ReferencePolicy.DYNAMIC,
                cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE
        ),
        @Reference(
                name = "imageTransformers",
                referenceInterface = ImageTransformer.class,
                policy = ReferencePolicy.DYNAMIC,
                cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE
        )
})
@Service(Servlet.class)
public class NamedTransformImageServlet extends SlingSafeMethodsServlet implements OptingServlet {

    private static final Logger log = LoggerFactory.getLogger(NamedTransformImageServlet.class);

    private static final Logger AVOID_USAGE_LOGGER =
          LoggerFactory.getLogger(NamedTransformImageServlet.class.getName() + ".AvoidUsage");

    public static final String NAME_IMAGE = "image";

    public static final String NAMED_IMAGE_FILENAME_PATTERN = "acs.commons.namedimage.filename.pattern";

    public static final String DEFAULT_FILENAME_PATTERN = "(image|img)\\.(.+)";

    public static final String RT_LOCAL_SOCIAL_IMAGE = "social:asiFile";

    public static final String RT_REMOTE_SOCIAL_IMAGE = "nt:adobesocialtype";

    private static final ValueMap EMPTY_PARAMS = new ValueMapDecorator(new LinkedHashMap<String, Object>());

    private static final String MIME_TYPE_PNG = "image/png";

    private static final String TYPE_QUALITY = "quality";

    private static final String TYPE_PROGRESSIVE = "progressive";
    private static final String PROP_ADD_URL_PARAMETERS = "addUrlParams";

    /* Asset Rendition Pattern Picker */
    private static final String DEFAULT_ASSET_RENDITION_PICKER_REGEX = "cq5dam\\.web\\.(.*)";
    private static final String TIFF_ORIENTATION = "tiff:Orientation";
    public static final String PARAM_SEPARATOR = ":";

    @Property(label = "Asset Rendition Picker Regex",
            description = "Regex to select the Rendition to transform when directly transforming a DAM Asset."
                    + " [ Default: cq5dam.web.(.*) ]",
            value = DEFAULT_ASSET_RENDITION_PICKER_REGEX)
    private static final String PROP_ASSET_RENDITION_PICKER_REGEX = "prop.asset-rendition-picker-regex";

    private final transient Map<String, NamedImageTransformer> namedImageTransformers =
            new ConcurrentHashMap<String, NamedImageTransformer>();

    private final transient Map<String, ImageTransformer> imageTransformers = new ConcurrentHashMap<String, ImageTransformer>();

    @Reference
    private transient MimeTypeService mimeTypeService;

    private Pattern lastSuffixPattern = Pattern.compile(DEFAULT_FILENAME_PATTERN);

    private transient RenditionPatternPicker renditionPatternPicker =
            new RenditionPatternPicker(Pattern.compile(DEFAULT_ASSET_RENDITION_PICKER_REGEX));
    
    /**
     * Only accept requests that.
     * - Are not null
     * - Have a suffix
     * - Whose first suffix segment is a registered transform name
     * - Whose last suffix matches the image file name pattern
     *
     * @param request SlingRequest object
     * @return true if the Servlet should handle the request
     */
    @Override
    public final boolean accepts(final SlingHttpServletRequest request) {
        if (request == null) {
            return false;
        }

        final String suffix = request.getRequestPathInfo().getSuffix();
        if (StringUtils.isBlank(suffix)) {
            return false;
        }

        final String transformName = PathInfoUtil.getFirstSuffixSegment(request);
        if (!this.namedImageTransformers.keySet().contains(transformName)) {
            return false;
        }

        final String lastSuffix = PathInfoUtil.getLastSuffixSegment(request);
        final Matcher matcher = lastSuffixPattern.matcher(lastSuffix);
        if (!matcher.matches()) {
            return false;
        }

        return true;
    }

    @Override
    protected final void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response) throws
            ServletException, IOException {

         // Warn when this servlet is used
         AVOID_USAGE_LOGGER.warn("An image is transformed on-the-fly, which can be a very resource intensive operation. "
              + "If done frequently, you should consider switching to dynamic AEM web-optimized images or creating such a rendition upfront using processing profiles. "
              + "See https://adobe-consulting-services.github.io/acs-aem-commons/features/named-image-transform/index.html for more details.");

        // Get the transform names from the suffix
        final List<NamedImageTransformer> selectedNamedImageTransformers = getNamedImageTransformers(request);

        // Collect and combine the image transformers and their params
        final ValueMap imageTransformersWithParams = getImageTransformersWithParams(selectedNamedImageTransformers);

        final Image image = resolveImage(request);
        final String mimeType = getMimeType(request, image);
        Layer layer = getLayer(image);

        // Adjust layer to image orientation
        processImageOrientation(image.getResource(), layer);
        
        if (layer == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        
        // Transform the image
        layer = this.transform(layer, imageTransformersWithParams, request);

        // Get the quality
        final double quality = this.getQuality(mimeType,
                imageTransformersWithParams.get(TYPE_QUALITY, EMPTY_PARAMS));

        // Check if the image is a JPEG which has to be encoded progressively
        final boolean progressiveJpeg = isProgressiveJpeg(mimeType,
                imageTransformersWithParams.get(TYPE_PROGRESSIVE, EMPTY_PARAMS));

        response.setContentType(mimeType);

        if (progressiveJpeg) {
            ProgressiveJpeg.write(layer, quality, response.getOutputStream());
        } else {
            layer.write(mimeType, quality, response.getOutputStream());
        }

        response.flushBuffer();
    }

    /**
     * Execute the ImageTransformers as specified by the Request's suffix segments against the Image layer.
     *
     * @param layer the Image layer
     * @param imageTransformersWithParams the transforms and their params
     * @return the transformed Image layer
     */
    protected final Layer transform(Layer layer, final ValueMap imageTransformersWithParams, SlingHttpServletRequest request) {
        for (final String type : imageTransformersWithParams.keySet()) {
            if (StringUtils.equals(TYPE_QUALITY, type)) {
                // Do not process the "quality" transform in the usual manner
                continue;
            }

            final ImageTransformer imageTransformer = this.imageTransformers.get(type);
            if (imageTransformer == null) {
                log.warn("Skipping transform. Missing ImageTransformer for type: {}", type);
                continue;
            }

            ValueMap transformParams = imageTransformersWithParams.get(type, EMPTY_PARAMS);

            if (transformParams != null) {
              if (Boolean.valueOf(transformParams.get(PROP_ADD_URL_PARAMETERS, false))) {
                LinkedHashMap<String, Object> cropParamsFromUrl = getCropParamsFromUrl(request);
                if(!cropParamsFromUrl.isEmpty()) {
                  transformParams = new ValueMapDecorator(new LinkedHashMap<String, Object>(transformParams));
                  transformParams.putAll(cropParamsFromUrl);
                }
              }

                layer = imageTransformer.transform(layer, transformParams);
            }
        }

        return layer;
    }

  /**
   * Rotate and flip image based on it's tiff:Orientation metadata.
   * @param imageResource image resource
   * @param layer image Layer object
   */
  protected void processImageOrientation(Resource imageResource, Layer layer) {
    ValueMap properties = getImageMetadataValueMap(imageResource);
    if(properties != null) {
      String orientation = properties.get(TIFF_ORIENTATION, String.class);
      if(orientation != null &&  Short.parseShort(orientation) != OrientationUtil.ORIENTATION_NORMAL) {
        switch(Short.parseShort(orientation)) {
          case OrientationUtil.ORIENTATION_MIRROR_HORIZONTAL:
            layer.flipHorizontally();
            break;
          case OrientationUtil.ORIENTATION_ROTATE_180:
            layer.rotate(180);
            break;
          case OrientationUtil.ORIENTATION_MIRROR_VERTICAL:
            layer.flipVertically();
            break;
          case OrientationUtil.ORIENTATION_MIRROR_HORIZONTAL_ROTATE_270_CW:
            layer.flipHorizontally();
            layer.rotate(270);
            break;
          case OrientationUtil.ORIENTATION_ROTATE_90_CW:
            layer.rotate(90);
            break;
          case OrientationUtil.ORIENTATION_MIRROR_HORIZONTAL_ROTATE_90_CW:
            layer.flipHorizontally();
            layer.rotate(90);
            break;
          case OrientationUtil.ORIENTATION_ROTATE_270_CW:
            layer.rotate(270);
            break;
          default:
            break;
        }
      }
    }
  }

  /**
   * Returns ValueMap of the Image Metadata resource
   * @param imageResource image resource
   * @return metadata ValueMap, or null if given resource doesn't have jcr:content/metadata node.
   */
  protected ValueMap getImageMetadataValueMap(Resource imageResource) {
    ValueMap result = null;
    final Resource metadata = imageResource.getChild("jcr:content/metadata");
    if (metadata != null) {
      result = metadata.adaptTo(ValueMap.class);
    }
    return result;
  }

  /**

    /**
     * Gets the NamedImageTransformers based on the Suffix segments in order.
     *
     * @param request the SlingHttpServletRequest object
     * @return a list of the NamedImageTransformers specified by the HTTP Request suffix segments
     */
    protected final List<NamedImageTransformer> getNamedImageTransformers(final SlingHttpServletRequest request) {
        final List<NamedImageTransformer> transformers = new ArrayList<NamedImageTransformer>();

        String[] suffixes = PathInfoUtil.getSuffixSegments(request);
        if (suffixes.length < 2) {
            log.warn("Named Transform Image Servlet requires at least one named transform");
            return transformers;
        }

        int endIndex = suffixes.length - 1;
        // Its OK to check; the above check ensures there are 2+ segments
        if (StringUtils.isNumeric(PathInfoUtil.getSuffixSegment(request, suffixes.length - 2))) {
            endIndex--;
        }

        suffixes = (String[]) ArrayUtils.subarray(suffixes, 0, endIndex);

        for (final String transformerName : suffixes) {
            final NamedImageTransformer transformer = this.namedImageTransformers.get(transformerName);
            if (transformer != null) {
                transformers.add(transformer);
            }
        }

        return transformers;
    }

    /**
     * Collect and combine the image transformers and their params.
     *
     * @param selectedNamedImageTransformers the named transformers and their params
     * @return the combined named image transformers and their params
     */
    protected final ValueMap getImageTransformersWithParams(
            final List<NamedImageTransformer> selectedNamedImageTransformers) {
        final ValueMap params = new ValueMapDecorator(new LinkedHashMap<String, Object>());

        for (final NamedImageTransformer namedImageTransformer : selectedNamedImageTransformers) {
            params.putAll(namedImageTransformer.getImageTransforms());
        }

        return params;
    }

    /**
     * Intelligently determines how to find the Image based on the associated SlingRequest.
     *
     * @param request the SlingRequest Obj
     * @return the Image object configured w the info of where the image to render is stored in CRX
     */
    protected final Image resolveImage(final SlingHttpServletRequest request) {
        final Resource resource = request.getResource();
        if (DamUtil.isAsset(resource)) {
            // For assets, pick the configured rendition if it exists
            // If rendition does not exist, use original
            return resolveImageAsAsset(resource);
        }

        if (DamUtil.isRendition(resource)
                || resource.isResourceType(JcrConstants.NT_FILE)
                || resource.isResourceType(JcrConstants.NT_RESOURCE)) {
            // For renditions; use the requested rendition
            final Image image = new Image(resource);
            image.set(DownloadResource.PN_REFERENCE, resource.getPath());
            return image;
        }

        final ResourceResolver resourceResolver = request.getResourceResolver();
        final PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
        final Page page = pageManager.getContainingPage(resource);
        if (page != null) {
            return resolveImageAsPage(page, resource);
        }

        if (resource.isResourceType(RT_LOCAL_SOCIAL_IMAGE)
                && resource.getValueMap().get("mimetype", StringUtils.EMPTY).startsWith("image/")) {
            // Is a UGC image
            return new SocialImageImpl(resource, NAME_IMAGE);
        }

        if (resource.isResourceType(RT_REMOTE_SOCIAL_IMAGE)) {
            // Is a UGC image
            return new SocialRemoteImageImpl(resource, NAME_IMAGE);
        }

        return new Image(resource);
    }

    private Image resolveImageAsAsset(final Resource resource) {
        final Asset asset = DamUtil.resolveToAsset(resource);
        Rendition rendition = asset.getRendition(renditionPatternPicker);

        if (rendition == null) {
            log.warn("Could not find rendition [ {} ] for [ {} ]", renditionPatternPicker,
                    resource.getPath());
            rendition = asset.getOriginal();
        }

        final Resource renditionResource = resource.getResourceResolver().getResource(rendition.getPath());

        final Image image = new Image(resource);
        image.set(DownloadResource.PN_REFERENCE, renditionResource.getPath());
        return image;
    }

    private Image resolveImageAsPage(final Page page, final Resource resource) {
        Resource contentResource = page.getContentResource();
        if (resource.isResourceType(NameConstants.NT_PAGE)
                || StringUtils.equals(resource.getPath(), contentResource.getPath())) {
            // Is a Page or Page's Content Resource; use the Page's image resource
            Page current = page;
            while (current != null && current.getContentResource(NAME_IMAGE) == null) {
                current = current.getParent();
            }

            if (current != null) {
                contentResource = current.getContentResource();
            }

            return new Image(contentResource, NAME_IMAGE);
        }

        return new Image(resource);
    }

    /**
     * Gets the mimeType of the image.
     * - The last segments suffix is looked at first and used
     * - if the last suffix segment's "extension" is .orig or .original then use the underlying resources mimeType
     * - else look up the mimeType to use based on this "extension"
     * - default to the resource's mimeType if the requested mimeType by extension is not supported.
     *
     * @param image the image to get the mimeType for
     * @return the string representation of the image's mimeType
     */
    private String getMimeType(final SlingHttpServletRequest request, final Image image) {
        final String lastSuffix = PathInfoUtil.getLastSuffixSegment(request);

        final String mimeType = mimeTypeService.getMimeType(lastSuffix);

        if (!StringUtils.endsWithIgnoreCase(lastSuffix, ".orig")
            && !StringUtils.endsWithIgnoreCase(lastSuffix, ".original")
            && (ImageIO.getImageWritersByMIMEType(mimeType).hasNext())) {
            return mimeType;
        } else {
            try {
                return image.getMimeType();
            } catch (final RepositoryException e) {
                return MIME_TYPE_PNG;
            }
        }
    }

    private LinkedHashMap<String, Object> getCropParamsFromUrl(SlingHttpServletRequest request) {
      LinkedHashMap<String, Object> urlParams = new LinkedHashMap<String, Object>();

      String transformName = PathInfoUtil.getFirstSuffixSegment(request);
      String extension = PathInfoUtil.getLastSuffixSegment(request);

      String paramsString = StringUtils.substringBetween(request.getRequestURI(), transformName + "/", extension);
      String[] params = StringUtils.split(paramsString, "/");
      for (String param : params) {
        urlParams.put(StringUtils.substringBefore(param, ":"),
              StringUtils.substringAfter(param, PARAM_SEPARATOR));
      }
      return urlParams;
  }

    /**
     * Gets the Image layer.
     *
     * @param image The Image to get the layer from
     * @return the image's Layer
     * @throws IOException
     */
    private Layer getLayer(final Image image) throws IOException {
        Layer layer = null;

        try {
            layer = image.getLayer(false, false, false);
        } catch (RepositoryException ex) {
            log.error("Could not create layer");
        }

        if (layer == null) {
            log.error("Could not create layer - layer is null;");
        } else {
            image.crop(layer);
            image.rotate(layer);
        }

        return layer;
    }


    /**
     * Computes the quality based on the "synthetic" Image Quality transform params
     *
     * Image Quality does not "transform" in the usual manner (it is not a simple layer manipulation)
     * thus this ad-hoc method is required to handle quality manipulation transformations.
     *
     * If "quality" key is no available in "transforms" the default of 82 is used (magic AEM Product quality setting)
     *
     * @param mimeType the desired image mimeType
     * @param transforms the map of image transform params
     * @return
     */
    protected final double getQuality(final String mimeType, final ValueMap transforms) {
        final String key = "quality"; // NOSONAR // replace with already existing constant
        final int defaultQuality = 82;
        final int maxQuality = 100;
        final int minQuality = 0;
        final int maxQualityGif = 255;
        final double oneHundred = 100D;

        log.debug("Transforming with [ quality ]");

        double quality = transforms.get(key, defaultQuality);

        if (quality > maxQuality || quality < minQuality) {
            quality = defaultQuality;
        }

        quality = quality / oneHundred;

        if (StringUtils.equals("image/gif", mimeType)) {
            quality = quality * maxQualityGif;
        }

        return quality;
    }

    /**
     * @param mimeType mime type string
     * @param transforms all transformers
     * @return <code>true</code> for jpeg mime types if progressive encoding is enabled
     */
    protected boolean isProgressiveJpeg(final String mimeType, final ValueMap transforms) {
        boolean enabled = transforms.get("enabled", false);
        if (enabled) {
            if ("image/jpeg".equals(mimeType) || "image/jpg".equals(mimeType)) {
                return true;
            } else {
                log.debug("Progressive encoding is only supported for JPEGs. Mime type: {}", mimeType);
                return false;
            }
        } else {
            return false;
        }
    }

    @Activate
    protected final void activate(final Map<String, String> properties) {
        final String regex = PropertiesUtil.toString(properties.get(PROP_ASSET_RENDITION_PICKER_REGEX),
                DEFAULT_ASSET_RENDITION_PICKER_REGEX);
        final String fileNameRegex = PropertiesUtil.toString(properties.get(NAMED_IMAGE_FILENAME_PATTERN),
                DEFAULT_FILENAME_PATTERN);
        if (StringUtils.isNotEmpty(fileNameRegex)) {
            lastSuffixPattern = Pattern.compile(fileNameRegex);
        }

        try {
            renditionPatternPicker = new RenditionPatternPicker(regex);
            log.info("Asset Rendition Pattern Picker: {}", regex);
        } catch (Exception ex) {
            log.error("Error creating RenditionPatternPicker with regex [ {} ], defaulting to [ {} ]", regex,
                    DEFAULT_ASSET_RENDITION_PICKER_REGEX);
            renditionPatternPicker = new RenditionPatternPicker(DEFAULT_ASSET_RENDITION_PICKER_REGEX);
        }

        /**
        * We want to be able to determine if the absence of the messages of the AVOID_USAGE_LOGGER
        * is caused by not using this feature or by disabling the WARN messages.
        */
        if (!AVOID_USAGE_LOGGER.isWarnEnabled()) {
          log.info("Warnings for the use of the NamedTransfomringImageServlet disabled");
        }

    }

    protected final void bindNamedImageTransformers(final NamedImageTransformer service,
                                                    final Map<Object, Object> props) {
        final String type = PropertiesUtil.toString(props.get(NamedImageTransformer.PROP_NAME), null);
        if (type != null) {
            this.namedImageTransformers.put(type, service);
        }
    }

    protected final void unbindNamedImageTransformers(final NamedImageTransformer service,
                                                      final Map<Object, Object> props) {
        final String type = PropertiesUtil.toString(props.get(NamedImageTransformer.PROP_NAME), null);
        if (type != null) {
            this.namedImageTransformers.remove(type);
        }
    }

    protected final void bindImageTransformers(final ImageTransformer service, final Map<Object, Object> props) {
        final String type = PropertiesUtil.toString(props.get(ImageTransformer.PROP_TYPE), null);
        if (type != null) {
            imageTransformers.put(type, service);
        }
    }

    protected final void unbindImageTransformers(final ImageTransformer service, final Map<Object, Object> props) {
        final String type = PropertiesUtil.toString(props.get(ImageTransformer.PROP_TYPE), null);
        if (type != null) {
            imageTransformers.remove(type);
        }
    }
}

