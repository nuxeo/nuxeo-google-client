/*
 * (C) Copyright 2015-2016 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Kevin Leturc
 */
package org.nuxeo.ecm.liveconnect.box;

import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.faces.application.Application;
import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.component.UINamingContainer;
import javax.faces.component.html.HtmlInputText;
import javax.faces.context.FacesContext;
import javax.faces.context.ResponseWriter;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.i18n.I18NUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.platform.oauth2.tokens.NuxeoOAuth2Token;
import org.nuxeo.ecm.platform.ui.web.component.file.InputFileChoice;
import org.nuxeo.ecm.platform.ui.web.component.file.InputFileInfo;
import org.nuxeo.ecm.platform.ui.web.component.file.JSFBlobUploader;
import org.nuxeo.ecm.platform.ui.web.util.ComponentUtils;
import org.nuxeo.runtime.api.Framework;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxFile;
import com.google.api.client.http.HttpStatusCodes;

/**
 * JSF Blob Upload based on box blobs.
 *
 * @since 8.1
 */
public class BoxBlobUploader implements JSFBlobUploader {

    private static final Log log = LogFactory.getLog(BoxBlobUploader.class);

    public static final String UPLOAD_BOX_FACET_NAME = InputFileChoice.UPLOAD + "Box";

    protected final String id;

    public BoxBlobUploader(String id) {
        this.id = id;
        try {
            getBoxBlobProvider();
        } catch (NuxeoException e) {
            // this exception is caught by JSFBlobUploaderDescriptor.getJSFBlobUploader
            // to mean that the uploader is not available because badly configured
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String getChoice() {
        return UPLOAD_BOX_FACET_NAME;
    }

    @Override
    public void hookSubComponent(UIInput parent) {
        Application app = FacesContext.getCurrentInstance().getApplication();
        ComponentUtils.initiateSubComponent(parent, UPLOAD_BOX_FACET_NAME,
                app.createComponent(HtmlInputText.COMPONENT_TYPE));
    }

    @Override
    public void encodeBeginUpload(UIInput parent, FacesContext context, String onClick) throws IOException {
        UIComponent facet = parent.getFacet(UPLOAD_BOX_FACET_NAME);
        if (!(facet instanceof HtmlInputText)) {
            return;
        }
        HtmlInputText inputText = (HtmlInputText) facet;

        // not ours to close
        @SuppressWarnings("resource")
        ResponseWriter writer = context.getResponseWriter();
        BoxOAuth2ServiceProvider provider = getBoxBlobProvider().getOAuth2Provider();

        String inputId = facet.getClientId(context);
        String prefix = parent.getClientId(context) + UINamingContainer.getSeparatorChar(context);
        String pickId = prefix + "BoxPickMsg";
        String infoId = prefix + "BoxInfo";
        String authorizationUrl = hasServiceAccount(provider) ? "" : getOAuthAuthorizationUrl(provider);
        Locale locale = context.getViewRoot().getLocale();
        String message;
        boolean isProviderAvailable = provider != null && provider.isProviderAvailable();

        writer.startElement("button", parent);
        writer.writeAttribute("type", "button", null);
        writer.writeAttribute("class", "button", null);

        // only add onclick event to button if oauth service provider is available
        // this prevents users from using the picker if some configuration is missing
        if (isProviderAvailable) {
            String onButtonClick = onClick
                    + ";"
                    + String.format("new nuxeo.utils.BoxPicker('%s', '%s','%s', '%s')", getClientId(provider), inputId,
                            infoId, authorizationUrl);
            writer.writeAttribute("onclick", onButtonClick, null);
        }

        writer.startElement("span", parent);
        writer.writeAttribute("id", pickId, null);
        message = I18NUtils.getMessageString("messages", "label.inputFile.boxUploadPicker", null, locale);
        writer.write(message);
        writer.endElement("span");

        writer.endElement("button");

        if (isProviderAvailable) {
            writer.write(ComponentUtils.WHITE_SPACE_CHARACTER);
            writer.startElement("span", parent);
            writer.writeAttribute("id", infoId, null);
            message = I18NUtils.getMessageString("messages", "error.inputFile.noFileSelected", null, locale);
            writer.write(message);
            writer.endElement("span");
        } else {
            // if oauth service provider not properly setup, add warning message
            writer.startElement("span", parent);
            writer.writeAttribute("class", "processMessage completeWarning", null);
            writer.writeAttribute(
                    "style",
                    "margin: 0 0 .5em 0; font-size: 11px; padding: 0.4em 0.5em 0.5em 2.2em; background-position-y: 0.6em",
                    null);
            message = I18NUtils.getMessageString("messages", "error.box.providerUnavailable", null, locale);
            writer.write(message);
            writer.endElement("span");
        }

        inputText.setLocalValueSet(false);
        inputText.setStyle("display: none");
        ComponentUtils.encodeComponent(context, inputText);
    }

    @Override
    public void validateUpload(UIInput parent, FacesContext context, InputFileInfo submitted) {
        UIComponent facet = parent.getFacet(UPLOAD_BOX_FACET_NAME);
        if (!(facet instanceof HtmlInputText)) {
            return;
        }
        HtmlInputText inputText = (HtmlInputText) facet;
        Object value = inputText.getSubmittedValue();
        if (value != null && !(value instanceof String)) {
            ComponentUtils.addErrorMessage(context, parent, "error.inputFile.invalidSpecialBlob");
            parent.setValid(false);
            return;
        }
        String string = (String) value;
        if (StringUtils.isBlank(string)) {
            String message = context.getPartialViewContext().isAjaxRequest() ? InputFileInfo.INVALID_WITH_AJAX_MESSAGE
                    : InputFileInfo.INVALID_FILE_MESSAGE;
            ComponentUtils.addErrorMessage(context, parent, message);
            parent.setValid(false);
            return;
        }

        BoxOAuth2ServiceProvider provider = getBoxBlobProvider().getOAuth2Provider();
        if (provider == null) {
            ComponentUtils.addErrorMessage(context, parent, "error.inputFile.boxInvalidConfiguration");
            parent.setValid(false);
            return;
        }

        String fileId = string;
        Optional<String> serviceUserId = getServiceUserId(provider, fileId, FacesContext.getCurrentInstance()
                                                                                        .getExternalContext()
                                                                                        .getUserPrincipal());
        if (!serviceUserId.isPresent()) {
            String link = String.format(
                    "<a href='#' onclick=\"openPopup('%s'); return false;\">Register a new token</a> and try again.",
                    getOAuthAuthorizationUrl(provider));
            ComponentUtils.addErrorMessage(context, parent, "error.inputFile.invalidPermissions", new Object[] { link });
            parent.setValid(false);
            return;
        }

        LiveConnectFileInfo fileInfo = new LiveConnectFileInfo(serviceUserId.get(), fileId);
        Blob blob = toBlob(fileInfo);
        submitted.setBlob(blob);
        submitted.setFilename(blob.getFilename());
        submitted.setMimeType(blob.getMimeType());
    }

    /**
     * Box upload button is added to the file widget if and only if Box OAuth service provider is enabled
     *
     * @return true if Box OAuth service provider is enabled or false otherwise.
     */
    @Override
    public boolean isEnabled() {
        BoxOAuth2ServiceProvider provider = getBoxBlobProvider().getOAuth2Provider();
        return provider != null && provider.isEnabled();
    }

    /**
     * Creates a Box managed blob.
     *
     * @param fileInfo the Box file info
     * @return the blob
     */
    protected Blob toBlob(LiveConnectFileInfo fileInfo) {
        try {
            return getBoxBlobProvider().toBlob(fileInfo);
        } catch (IOException e) {
            throw new RuntimeException(e); // TODO better feedback
        }
    }

    protected String getClientId(BoxOAuth2ServiceProvider provider) {
        return Optional.ofNullable(provider).map(BoxOAuth2ServiceProvider::getClientId).orElse("");
    }

    protected BoxBlobProvider getBoxBlobProvider() {
        return (BoxBlobProvider) Framework.getService(BlobManager.class).getBlobProvider(id);
    }

    /**
     * Iterates all registered Box tokens of a {@link Principal} to get the serviceLogin of a token with access to a Box
     * file. We need this because Box file picker doesn't provide any information about the account that was used to
     * select the file, and therefore we need to "guess".
     *
     * @param provider
     * @param fileId
     * @param principal
     * @return
     */
    private Optional<String> getServiceUserId(BoxOAuth2ServiceProvider provider, String fileId, Principal principal) {
        Map<String, Serializable> filter = new HashMap<>();
        filter.put("nuxeoLogin", principal.getName());

        return provider.getCredentialDataStore()
                       .query(filter)
                       .stream()
                       .map(NuxeoOAuth2Token::new)
                       .filter(token -> hasAccessToFile(token, fileId))
                       .map(NuxeoOAuth2Token::getServiceLogin)
                       .findFirst();
    }

    /**
     * Attempts to retrieve a Box file's metadata to check if an accessToken has permissions to access the file.
     *
     * @param token
     * @param fileId
     * @return true if metadata was successfully retrieved, or false otherwise.
     */
    private boolean hasAccessToFile(NuxeoOAuth2Token token, String fileId) {
        try {
            BoxAPIConnection client = getBoxBlobProvider().getBoxClient(token);
            return new BoxFile(client, fileId).getInfo("size") != null;
        } catch (IOException e) {
            throw new RuntimeException(e); // TODO better feedback
        } catch (BoxAPIException e) {
            // Unauthorized
            return e.getResponseCode() == HttpStatusCodes.STATUS_CODE_UNAUTHORIZED;
        }
    }

    private boolean hasServiceAccount(BoxOAuth2ServiceProvider provider) {
        HttpServletRequest request = getHttpServletRequest();
        String username = request.getUserPrincipal().getName();
        return provider != null && provider.getServiceUser(username) != null;
    }

    private String getOAuthAuthorizationUrl(BoxOAuth2ServiceProvider provider) {
        HttpServletRequest request = getHttpServletRequest();
        return (provider != null && provider.getClientId() != null) ? provider.getAuthorizationUrl(request) : "";
    }

    private HttpServletRequest getHttpServletRequest() {
        return (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
    }
}
