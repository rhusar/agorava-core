/*
 * Copyright 2014 Agorava
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.agorava.cdi.extensions;

import org.agorava.AgoravaConstants;
import org.agorava.AgoravaContext;
import org.agorava.api.atinject.InjectWithQualifier;
import org.agorava.api.atinject.ProviderRelated;
import org.agorava.api.exception.AgoravaException;
import org.agorava.api.oauth.OAuth;
import org.agorava.api.oauth.OAuthService;
import org.agorava.api.oauth.OAuthSession;
import org.agorava.api.oauth.application.OAuthAppSettings;
import org.agorava.api.oauth.application.OAuthAppSettingsBuilder;
import org.agorava.api.oauth.application.OAuthApplication;
import org.agorava.cdi.CurrentLiteral;
import org.agorava.cdi.resolver.ApplicationResolver;
import org.agorava.spi.ProviderConfigOauth;
import org.apache.deltaspike.core.api.config.ConfigResolver;
import org.apache.deltaspike.core.api.literal.AnyLiteral;
import org.apache.deltaspike.core.util.bean.BeanBuilder;
import org.apache.deltaspike.core.util.bean.WrappingBeanBuilder;
import org.apache.deltaspike.core.util.metadata.builder.AnnotatedTypeBuilder;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.agorava.cdi.extensions.AnnotationUtils.getAnnotationsWithMeta;
import static org.agorava.cdi.extensions.AnnotationUtils.getSingleProviderRelatedQualifier;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.AnnotatedMember;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessBean;
import javax.enterprise.inject.spi.ProcessProducer;
import javax.enterprise.inject.spi.ProcessProducerMethod;
import javax.enterprise.inject.spi.Producer;
import javax.inject.Named;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Agorava CDI extension to discover existing module and configured modules
 *
 * @author Antoine Sabot-Durand
 */
public class AgoravaExtension extends AgoravaContext implements Extension, Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger log = Logger.getLogger(AgoravaExtension.class.getName());
    private static Bean osb;
    private Set<Annotation> providerQualifiersConfigured = new HashSet<Annotation>();
    private Map<OAuth.OAuthVersion, Class<? extends OAuthService>> version2ServiceClass = new HashMap<OAuth.OAuthVersion,
            Class<? extends OAuthService>>();
    private Map<Annotation, OAuth.OAuthVersion> providerQualifiers2Version = new HashMap<Annotation, OAuth.OAuthVersion>();

    /**
     * @return the set of all service's qualifiers present in the application
     */
    public static Collection<Annotation> getServicesQualifiersAvailable() {
        return getServicesToQualifier().values();
    }

    //-------------------- Utilities -------------------------------------

    void applyQualifier(Annotation qual, AnnotatedTypeBuilder<?> atb) {
        AnnotatedType<?> at = atb.create();

        //do a loop on all field to replace annotation mark by CDI annotations
        for (AnnotatedField af : at.getFields())
            if (af.isAnnotationPresent(InjectWithQualifier.class)) {
                atb.addToField(af, InjectLiteral.instance);
                atb.addToField(af, qual);

            }

        //loop on constructors to do the same
        for (AnnotatedConstructor ac : at.getConstructors()) {
            Annotation[][] pa = ac.getJavaMember().getParameterAnnotations();
            //loop on args to detect marked param
            for (int i = 0; i < pa.length; i++)
                for (int j = 0; j < pa[i].length; j++)
                    if (pa[i][j].equals(InjectWithQualifierLiteral.instance)) {
                        atb.addToConstructor(ac, InjectLiteral.instance);
                        atb.addToConstructorParameter(ac.getJavaMember(), i, qual);
                    }

        }

        //loop on other methods (setters)
        for (AnnotatedMethod am : at.getMethods())
            if (am.isAnnotationPresent(InjectWithQualifierLiteral.class)) {
                atb.addToMethod(am, InjectLiteral.instance);
                atb.addToMethod(am, qual);
            }

    }

    //----------------- Before Bean discovery Phase ----------------------------------

    /**
     * This observer is started at the beginning of the extension
     *
     * @param bbd
     */
    public void launchExtension(@Observes BeforeBeanDiscovery bbd) {
        log.info("Starting Agorava Framework initialization");
    }

    //----------------- Process AnnotatedType Phase ----------------------------------

    public void processOauthService(@Observes ProcessAnnotatedType<? extends OAuthService> pat) {
        pat.veto();
        AnnotatedType<? extends OAuthService> at = pat.getAnnotatedType();
        Class<? extends OAuthService> clazz = (Class<? extends OAuthService>) at.getBaseType();
        OAuth qualOauth = at.getAnnotation(OAuth.class);
        if (qualOauth != null)
            version2ServiceClass.put(qualOauth.value(), clazz);
    }

    //----------------- Process Producer Phase ----------------------------------

    /**
     * This observer decorates the produced {@link org.agorava.api.oauth.application.OAuthAppSettings} by injecting
     * its own qualifier and service name
     * and build the list of Qualifiers bearing the ProviderRelated meta annotation (configured services)
     *
     * @param pp the Process producer event
     */
    public void processOAuthSettingsProducer(@Observes final ProcessProducer<?, OAuthAppSettings> pp) {
        final AnnotatedMember<OAuthAppSettings> annotatedMember = (AnnotatedMember<OAuthAppSettings>) pp.getAnnotatedMember();

        log.log(INFO, "Qualifiers configured {0}", providerQualifiersConfigured);
        Annotation qual = null;
        try {
            qual = getSingleProviderRelatedQualifier(annotatedMember, false);
        } catch (AgoravaException e) {
            pp.addDefinitionError(new AgoravaException("OAuthAppSettings producers should be annotated with a Service " +
                    "Provider on " + annotatedMember.getJavaMember().getName() + " in " + annotatedMember.getJavaMember()
                    .getDeclaringClass()));
        }

        if (annotatedMember.isAnnotationPresent(OAuthApplication.class)) {
            if (annotatedMember instanceof AnnotatedField) {

                final OAuthApplication app = annotatedMember.getAnnotation(OAuthApplication.class);

                OAuthAppSettingsBuilder builderOAuthApp = null;
                try {
                    builderOAuthApp = app.builder().newInstance();
                } catch (Exception e) {
                    pp.addDefinitionError(new AgoravaException("Unable to create Settings Builder with class " +
                            app.builder(), e));
                }

                builderOAuthApp.qualifier(qual)
                        .params(app.params());

                pp.setProducer(new OAuthAppSettingsProducerWithBuilder(builderOAuthApp, qual));
            } else
                pp.addDefinitionError(new AgoravaException("@OAuthApplication are only supported on Field. Agorava cannot " +
                        "process producer " + annotatedMember.getJavaMember().getName() + " in class " + annotatedMember
                        .getJavaMember().getDeclaringClass()));
        } else {
            final Producer<OAuthAppSettings> oldProducer = pp.getProducer();
            pp.setProducer(new OAuthAppSettingsProducerDecorator(oldProducer, qual));
        }

        log.log(INFO, "Found settings for {0}", qual);
        providerQualifiersConfigured.add(qual);
    }

    //----------------- Process Bean Phase ----------------------------------

    /*
     * This does practically not do much anymore after the discovery was moved
     * to AfterDeploymentValidation. see https://issues.jboss.org/browse/CDI-274
     * Kept around to do simple deployment validation of ProviderRelated qualifier.
     */
    private void CommonsProcessOAuthTier(ProcessBean<? extends ProviderConfigOauth> pb) {

        Annotated annotated = pb.getAnnotated();
        Set<Annotation> qualifiers = getAnnotationsWithMeta(annotated, ProviderRelated.class);
        if (qualifiers.size() != 1)
            throw new AgoravaException("A RemoteService bean should have one and only one service related Qualifier : " + pb
                    .getAnnotated().toString());

        Class<? extends ProviderConfigOauth> clazz = (Class<? extends ProviderConfigOauth>) pb.getBean().getBeanClass();
        try {
            providerQualifiers2Version.put(getSingleProviderRelatedQualifier(qualifiers, true),
                    clazz.newInstance().getOAuthVersion());
        } catch (Exception e) {
            throw new AgoravaException("Error while retrieving version of OAuth in tier config", e);
        }
    }

    public void processRemoteServiceRoot(@Observes ProcessBean<? extends ProviderConfigOauth> pb) {
        CommonsProcessOAuthTier(pb);
    }

    private void captureGenericOAuthService(@Observes ProcessBean<? extends OAuthService> pb) {
        Bean<? extends OAuthService> bean = pb.getBean();
        if (bean.getQualifiers().contains(GenericBeanLiteral.INSTANCE)) {
            log.info("here");
        }
    }

    private void captureOauthSessionProducer(@Observes ProcessProducerMethod<OAuthSession, ?> pb) {
        if (!pb.getAnnotated().isAnnotationPresent(Named.class))
            osb = pb.getBean();
    }

    /*
    private void captureGenericOAuthProvider(@Observes ProcessBean<? extends OAuthProvider> pb) {
        Bean<? extends OAuthProvider> bean = pb.getBean();

        if (bean.getQualifiers().contains(GenericBeanLiteral.INSTANCE)) {
            for (Annotation annotation : bean.getQualifiers()) {
                if (annotation instanceof OAuth) {
                    version2ServiceClass.put(((OAuth) annotation).value(), bean);

                }
            }
        }
    }*/

    //----------------- Process After Bean Discovery Phase ----------------------------------

    private <T> void beanRegisterer(Class<T> clazz, Annotation qual, Class<? extends Annotation> scope, AfterBeanDiscovery abd,
            BeanManager beanManager, Type... types) {

        AnnotatedTypeBuilder<T> atb = new AnnotatedTypeBuilder<T>()
                .readFromType(clazz)
                .addToClass(qual)
                .setJavaClass(clazz);

        applyQualifier(qual, atb);

        BeanBuilder<T> providerBuilder = new BeanBuilder<T>(beanManager)
                .readFromType(atb.create())
                .scope(scope)
                .passivationCapable(true)
                .addTypes(types);

        Bean<T> newBean = providerBuilder.create();
        abd.addBean(newBean);
    }

    /**
     * After all {@link org.agorava.api.oauth.application.OAuthAppSettings} were discovered we get their bean to
     * retrieve the actual name of Social Media
     * and associates it with the corresponding Qualifier
     *
     * @param abd
     * @param beanManager
     */
    public void registerGenericBeans(@Observes AfterBeanDiscovery abd, BeanManager beanManager) {

        for (Annotation qual : providerQualifiersConfigured) {
            OAuth.OAuthVersion version = providerQualifiers2Version.get(qual);
            if (version == null) {
                abd.addDefinitionError(new AgoravaException("There is no OAuth version associated to provider related " +
                        "Qualifier " +
                        "" + qual));
            }

            Class clazz = version2ServiceClass.get(version);
            if (clazz == null) {
                abd.addDefinitionError(new AgoravaException("There is no OAuth Service class for OAuth version " + version));
            }

            beanRegisterer(clazz, qual, Dependent.class, abd, beanManager, OAuthService.class);
        }

        // Adding all Provider Related qualifier on Session producer
        if (osb == null) {
            abd.addDefinitionError(new AgoravaException("Application didn't provided OAuthSession bean. You should add one " +
                    "via a producer or activate automatic management by adding " + ApplicationResolver.RESOLVER +
                    "=<application|session|request|cookie> in " +
                    "agorava.properties file"));
        } else {
            WrappingBeanBuilder<OAuthSession> wbp = new WrappingBeanBuilder<OAuthSession>(osb, beanManager);
            wbp.readFromType(beanManager.createAnnotatedType(OAuthSession.class)).qualifiers(providerQualifiersConfigured)
                    .addQualifiers(new AnyLiteral(), CurrentLiteral.INSTANCE).scope(Dependent.class);
            Bean res = wbp.create();
            abd.addBean(res);
        }

    }

    //--------------------- After Deployment validation phase

    public void endOfExtension(@Observes AfterDeploymentValidation adv, BeanManager beanManager) {

        registerServiceNames(beanManager);

        new BeanResolverCdi();

        producerScope = ConfigResolver.getPropertyValue("producerScope", "");
        internalCallBack = ConfigResolver.getPropertyValue(AgoravaConstants.INTERN_CALLBACK_PARAM);
        if (internalCallBack == null) {
            log.warning("No internal callback defined, it's defaulted to home page");
            internalCallBack = "/";
        }

        log.info("Agorava initialization complete");
    }

    private void registerServiceNames(BeanManager beanManager) {
        Set<Bean<?>> beans = beanManager.getBeans(ProviderConfigOauth.class, new AnyLiteral());

        for (Bean<?> bean : beans) {
            Annotation qual = getSingleProviderRelatedQualifier(bean.getQualifiers(), false);
            CreationalContext<?> ctx = beanManager.createCreationalContext(null);
            final ProviderConfigOauth tierConfig = (ProviderConfigOauth) beanManager.getReference(bean,
                    ProviderConfigOauth.class, ctx);
            String name = tierConfig.getProviderName();
            getServicesToQualifier().put(name, qual);
            ctx.release();
        }
        if (providerQualifiersConfigured.size() != getServicesToQualifier().size())
            log.log(WARNING, "Some Service modules present in the application are not configured so won't be available");
        //TODO:list the service without config

    }


}
