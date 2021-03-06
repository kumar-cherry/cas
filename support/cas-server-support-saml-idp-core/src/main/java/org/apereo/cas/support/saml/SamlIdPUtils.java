package org.apereo.cas.support.saml;

import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.saml.services.SamlRegisteredService;
import org.apereo.cas.support.saml.services.idp.metadata.SamlRegisteredServiceServiceProviderMetadataFacade;
import org.apereo.cas.support.saml.services.idp.metadata.cache.SamlRegisteredServiceCachingMetadataResolver;
import org.apereo.cas.util.CollectionUtils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import org.apache.commons.lang3.StringUtils;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.messaging.context.SAMLEndpointContext;
import org.opensaml.saml.common.messaging.context.SAMLPeerEntityContext;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.criterion.BindingCriterion;
import org.opensaml.saml.criterion.EntityRoleCriterion;
import org.opensaml.saml.metadata.resolver.ChainingMetadataResolver;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.metadata.resolver.RoleDescriptorResolver;
import org.opensaml.saml.metadata.resolver.impl.PredicateRoleDescriptorResolver;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.RequestAbstractType;
import org.opensaml.saml.saml2.core.StatusResponseType;
import org.opensaml.saml.saml2.metadata.AssertionConsumerService;
import org.opensaml.saml.saml2.metadata.Endpoint;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.saml2.metadata.impl.AssertionConsumerServiceBuilder;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This is {@link SamlIdPUtils}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Slf4j
@UtilityClass
public class SamlIdPUtils {

    /**
     * Prepare peer entity saml endpoint.
     *
     * @param request         the authn request
     * @param outboundContext the outbound context
     * @param adaptor         the adaptor
     * @param binding         the binding
     * @throws SamlException the saml exception
     */
    public static void preparePeerEntitySamlEndpointContext(final RequestAbstractType request,
                                                            final MessageContext outboundContext,
                                                            final SamlRegisteredServiceServiceProviderMetadataFacade adaptor,
                                                            final String binding) throws SamlException {
        val entityId = adaptor.getEntityId();
        if (!adaptor.containsAssertionConsumerServices()) {
            throw new SamlException("No assertion consumer service could be found for entity " + entityId);
        }

        val peerEntityContext = outboundContext.getSubcontext(SAMLPeerEntityContext.class, true);
        if (peerEntityContext == null) {
            throw new SamlException("SAMLPeerEntityContext could not be defined for entity " + entityId);
        }
        peerEntityContext.setEntityId(entityId);

        val endpointContext = peerEntityContext.getSubcontext(SAMLEndpointContext.class, true);
        if (endpointContext == null) {
            throw new SamlException("SAMLEndpointContext could not be defined for entity " + entityId);
        }

        val endpoint = determineEndpointForRequest(request, adaptor, binding);
        LOGGER.debug("Configured peer entity endpoint to be [{}] with binding [{}]", endpoint.getLocation(), endpoint.getBinding());
        endpointContext.setEndpoint(endpoint);
    }

    /**
     * Determine assertion consumer service assertion consumer service.
     *
     * @param authnRequest the authn request
     * @param adaptor      the adaptor
     * @param binding      the binding
     * @return the assertion consumer service
     */
    public static Endpoint determineEndpointForRequest(final RequestAbstractType authnRequest,
                                                       final SamlRegisteredServiceServiceProviderMetadataFacade adaptor,
                                                       final String binding) {
        var endpoint = (Endpoint) null;
        if (authnRequest instanceof LogoutRequest) {
            endpoint = adaptor.getSingleLogoutService(binding);
        } else {
            val endpointReq = getAssertionConsumerServiceFromRequest(authnRequest, binding);
            endpoint = endpointReq == null
                ? adaptor.getAssertionConsumerService(binding)
                : endpointReq;
        }

        if (endpoint == null || StringUtils.isBlank(endpoint.getBinding())) {
            throw new SamlException("Assertion consumer service does not define a binding");
        }
        val location = StringUtils.isBlank(endpoint.getResponseLocation()) ? endpoint.getLocation() : endpoint.getResponseLocation();
        if (StringUtils.isBlank(location)) {
            throw new SamlException("Assertion consumer service does not define a target location");
        }
        return endpoint;
    }

    private static AssertionConsumerService getAssertionConsumerServiceFromRequest(final RequestAbstractType authnRequest, final String binding) {
        if (authnRequest instanceof AuthnRequest) {
            val acsUrl = AuthnRequest.class.cast(authnRequest).getAssertionConsumerServiceURL();
            if (StringUtils.isBlank(acsUrl)) {
                return null;
            }
            LOGGER.debug("Using assertion consumer service url [{}] with binding [{}] from authentication request", acsUrl, binding);
            val builder = new AssertionConsumerServiceBuilder();
            val endpoint = builder.buildObject(AssertionConsumerService.DEFAULT_ELEMENT_NAME);
            endpoint.setBinding(binding);
            endpoint.setResponseLocation(acsUrl);
            endpoint.setLocation(acsUrl);
            return endpoint;
        }
        return null;
    }

    /**
     * Gets chaining metadata resolver for all saml services.
     *
     * @param servicesManager the services manager
     * @param entityID        the entity id
     * @param resolver        the resolver
     * @return the chaining metadata resolver for all saml services
     */
    @SneakyThrows
    @SuppressFBWarnings("PRMC_POSSIBLY_REDUNDANT_METHOD_CALLS")
    public static MetadataResolver getMetadataResolverForAllSamlServices(final ServicesManager servicesManager,
                                                                         final String entityID,
                                                                         final SamlRegisteredServiceCachingMetadataResolver resolver) {

        val registeredServices = servicesManager.findServiceBy(SamlRegisteredService.class::isInstance);
        val chainingMetadataResolver = new ChainingMetadataResolver();

        val resolvers = registeredServices.stream()
            .filter(SamlRegisteredService.class::isInstance)
            .map(SamlRegisteredService.class::cast)
            .map(s -> SamlRegisteredServiceServiceProviderMetadataFacade.get(resolver, s, entityID))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(SamlRegisteredServiceServiceProviderMetadataFacade::getMetadataResolver)
            .collect(Collectors.toList());

        LOGGER.debug("Located [{}] metadata resolvers to match against [{}]", resolvers, entityID);

        chainingMetadataResolver.setResolvers(resolvers);
        chainingMetadataResolver.setId(entityID);
        chainingMetadataResolver.initialize();
        return chainingMetadataResolver;
    }

    /**
     * Gets assertion consumer service for.
     *
     * @param authnRequest    the authn request
     * @param servicesManager the services manager
     * @param resolver        the resolver
     * @return the assertion consumer service for
     */
    public static AssertionConsumerService getAssertionConsumerServiceFor(final AuthnRequest authnRequest,
                                                                          final ServicesManager servicesManager,
                                                                          final SamlRegisteredServiceCachingMetadataResolver resolver) {
        try {
            val acs = new AssertionConsumerServiceBuilder().buildObject();
            if (authnRequest.getAssertionConsumerServiceIndex() != null) {
                val issuer = getIssuerFromSamlRequest(authnRequest);
                val samlResolver = getMetadataResolverForAllSamlServices(servicesManager, issuer, resolver);
                val criteriaSet = new CriteriaSet();
                criteriaSet.add(new EntityIdCriterion(issuer));
                criteriaSet.add(new EntityRoleCriterion(SPSSODescriptor.DEFAULT_ELEMENT_NAME));
                criteriaSet.add(new BindingCriterion(CollectionUtils.wrap(SAMLConstants.SAML2_POST_BINDING_URI)));

                val it = samlResolver.resolve(criteriaSet);
                it.forEach(entityDescriptor -> {
                    val spssoDescriptor = entityDescriptor.getSPSSODescriptor(SAMLConstants.SAML20P_NS);
                    val acsEndpoints = spssoDescriptor.getAssertionConsumerServices();
                    if (acsEndpoints.isEmpty()) {
                        throw new IllegalArgumentException("Metadata resolved for entity id " + issuer + " has no defined ACS endpoints");
                    }
                    val acsIndex = authnRequest.getAssertionConsumerServiceIndex();
                    if (acsIndex + 1 > acsEndpoints.size()) {
                        throw new IllegalArgumentException("AssertionConsumerService index specified in the request " + acsIndex + " is invalid "
                            + "since the total endpoints available to " + issuer + " is " + acsEndpoints.size());
                    }
                    val foundAcs = acsEndpoints.get(acsIndex);
                    acs.setBinding(foundAcs.getBinding());
                    acs.setLocation(foundAcs.getLocation());
                    acs.setResponseLocation(foundAcs.getResponseLocation());
                    acs.setIndex(acsIndex);
                });
            } else {
                acs.setBinding(authnRequest.getProtocolBinding());
                acs.setLocation(authnRequest.getAssertionConsumerServiceURL());
                acs.setResponseLocation(authnRequest.getAssertionConsumerServiceURL());
                acs.setIndex(0);
                acs.setIsDefault(Boolean.TRUE);
            }

            LOGGER.debug("Resolved AssertionConsumerService from the request is [{}]", acs);
            if (StringUtils.isBlank(acs.getBinding())) {
                throw new SamlException("AssertionConsumerService has no protocol binding defined");
            }
            if (StringUtils.isBlank(acs.getLocation()) && StringUtils.isBlank(acs.getResponseLocation())) {
                throw new SamlException("AssertionConsumerService has no location or response location defined");
            }
            return acs;
        } catch (final Exception e) {
            throw new IllegalArgumentException(new SamlException(e.getMessage(), e));
        }
    }

    /**
     * Gets issuer from saml request.
     *
     * @param request the request
     * @return the issuer from saml request
     */
    private static String getIssuerFromSamlRequest(final RequestAbstractType request) {
        return request.getIssuer().getValue();
    }

    /**
     * Gets issuer from saml response.
     *
     * @param response the response
     * @return the issuer from saml response
     */
    private static String getIssuerFromSamlResponse(final StatusResponseType response) {
        return response.getIssuer().getValue();
    }

    /**
     * Gets issuer from saml object.
     *
     * @param object the object
     * @return the issuer from saml object
     */
    public static String getIssuerFromSamlObject(final SAMLObject object) {
        if (object instanceof RequestAbstractType) {
            return RequestAbstractType.class.cast(object).getIssuer().getValue();
        }
        if (object instanceof StatusResponseType) {
            return StatusResponseType.class.cast(object).getIssuer().getValue();
        }
        return null;
    }

    /**
     * Gets role descriptor resolver.
     *
     * @param adaptor              the adaptor
     * @param requireValidMetadata the require valid metadata
     * @return the role descriptor resolver
     * @throws Exception the exception
     */
    public static RoleDescriptorResolver getRoleDescriptorResolver(final SamlRegisteredServiceServiceProviderMetadataFacade adaptor,
                                                                   final boolean requireValidMetadata) throws Exception {
        return getRoleDescriptorResolver(adaptor.getMetadataResolver(), requireValidMetadata);
    }

    /**
     * Gets role descriptor resolver.
     *
     * @param metadata             the metadata
     * @param requireValidMetadata the require valid metadata
     * @return the role descriptor resolver
     * @throws Exception the exception
     */
    public static RoleDescriptorResolver getRoleDescriptorResolver(final MetadataResolver metadata,
                                                                   final boolean requireValidMetadata) throws Exception {
        val roleDescriptorResolver = new PredicateRoleDescriptorResolver(metadata);
        roleDescriptorResolver.setSatisfyAnyPredicates(true);
        roleDescriptorResolver.setUseDefaultPredicateRegistry(true);
        roleDescriptorResolver.setRequireValidMetadata(requireValidMetadata);
        roleDescriptorResolver.initialize();
        return roleDescriptorResolver;
    }
}


