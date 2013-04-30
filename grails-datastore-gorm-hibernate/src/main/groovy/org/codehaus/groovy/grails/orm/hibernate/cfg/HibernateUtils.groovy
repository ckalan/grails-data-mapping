package org.codehaus.groovy.grails.orm.hibernate.cfg

import grails.artefact.Enhanced
import grails.util.GrailsNameUtils
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode

import org.apache.commons.beanutils.PropertyUtils
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.commons.GrailsClassUtils
import org.codehaus.groovy.grails.commons.GrailsDomainClass
import org.codehaus.groovy.grails.commons.GrailsDomainClassProperty
import org.codehaus.groovy.grails.orm.hibernate.HibernateDatastore
import org.codehaus.groovy.grails.orm.hibernate.HibernateGormEnhancer
import org.codehaus.groovy.grails.orm.hibernate.HibernateGormInstanceApi
import org.codehaus.groovy.grails.orm.hibernate.HibernateGormStaticApi
import org.codehaus.groovy.grails.orm.hibernate.HibernateGormValidationApi
import org.codehaus.groovy.grails.orm.hibernate.support.ClosureEventTriggeringInterceptor
import org.codehaus.groovy.grails.plugins.DomainClassGrailsPlugin
import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.runtime.StringGroovyMethods
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.hibernate.FlushMode
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.proxy.HibernateProxy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.SimpleTypeConverter
import org.springframework.context.ApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.orm.hibernate3.HibernateCallback
import org.springframework.orm.hibernate3.HibernateTemplate
import org.springframework.transaction.PlatformTransactionManager

@CompileStatic
class HibernateUtils {

    static final Logger LOG = LoggerFactory.getLogger(HibernateUtils)

    /**
     * Overrides a getter on a property that is a Hibernate proxy in order to make sure the initialized object is returned hence avoiding Hibernate proxy hell.
     */
    static void handleLazyProxy(GrailsDomainClass domainClass, GrailsDomainClassProperty property) {
        // don't do anything
        // return lazy proxies
    }

    static void enhanceSessionFactories(ApplicationContext ctx, GrailsApplication grailsApplication, Object source = null) {

        Map<SessionFactory, HibernateDatastore> datastores = [:]

        for (entry in ctx.getBeansOfType(SessionFactory).entrySet()) {
            SessionFactory sessionFactory = entry.value
            String beanName = entry.key
            String suffix = beanName - 'sessionFactory'
            enhanceSessionFactory sessionFactory, grailsApplication, ctx, suffix, datastores, source
        }

        ctx.getBean("eventTriggeringInterceptor", ClosureEventTriggeringInterceptor).datastores = datastores
    }

    static enhanceSessionFactory(SessionFactory sessionFactory, GrailsApplication application, ApplicationContext ctx) {
        enhanceSessionFactory(sessionFactory, application, ctx, '', [:])
    }

    static enhanceSessionFactory(SessionFactory sessionFactory, GrailsApplication application,
            ApplicationContext ctx, String suffix, Map<SessionFactory, HibernateDatastore> datastores, Object source = null) {

        MappingContext mappingContext = ctx.getBean("grailsDomainClassMappingContext", MappingContext)
        PlatformTransactionManager transactionManager = ctx.getBean("transactionManager$suffix", PlatformTransactionManager)
        HibernateDatastore datastore = (HibernateDatastore)ctx.getBean("hibernateDatastore$suffix", Datastore)
        datastores[sessionFactory] = datastore
        String datasourceName = suffix ? suffix[1..-1] : GrailsDomainClassProperty.DEFAULT_DATA_SOURCE

        HibernateGormEnhancer enhancer = new HibernateGormEnhancer(datastore, transactionManager, application)

        def enhanceEntity = { PersistentEntity entity ->
            GrailsDomainClass dc = (GrailsDomainClass)application.getArtefact(DomainClassArtefactHandler.TYPE, entity.javaClass.name)
            if (!GrailsHibernateUtil.isMappedWithHibernate(dc) || !GrailsHibernateUtil.usesDatasource(dc, datasourceName)) {
                return
            }

            if (!datasourceName.equals(GrailsDomainClassProperty.DEFAULT_DATA_SOURCE)) {
                LOG.debug "Registering namespace methods for $dc.clazz.name in DataSource '$datasourceName'"
                registerNamespaceMethods dc, datastore, datasourceName, transactionManager, application
            }

            if (datasourceName.equals(GrailsDomainClassProperty.DEFAULT_DATA_SOURCE) || datasourceName.equals(GrailsHibernateUtil.getDefaultDataSource(dc))) {
                LOG.debug "Enhancing GORM entity ${entity.name}"
                if (entity.javaClass.getAnnotation(Enhanced) == null) {
                    enhancer.enhance entity
                }
                else {
                    enhancer.enhance entity, true
                }

                DomainClassGrailsPlugin.addRelationshipManagementMethods(dc, ctx)
            }
        }

        // If we are reloading via an onChange event, the source indicates the specific
        // entity that needs to be reloaded. Otherwise, just reload all of them.
        if (source) {
            PersistentEntity entity = getPersistentEntity(mappingContext, InvokerHelper.getPropertySafe(source, 'name')?.toString())
            if (entity) {
                enhanceEntity(entity)
            }
        }
        else {
            for (PersistentEntity entity in mappingContext.getPersistentEntities()) {
                enhanceEntity(entity)
            }
        }
    }
    
    // workaround CS bug
    @CompileStatic(TypeCheckingMode.SKIP)
    private static PersistentEntity getPersistentEntity(mappingContext, String name) {
        mappingContext.getPersistentEntity(name)
    }

    static Map filterQueryArgumentMap(Map query) {
        def queryArgs = [:]
        for (entry in query.entrySet()) {
            if (entry.value instanceof CharSequence) {
                queryArgs[entry.key] = entry.value.toString()
            }
            else {
                queryArgs[entry.key] = entry.value
            }
        }
        return queryArgs
    }

    private static List<String> removeNullNames(Map query) {
        List<String> nullNames = []
        Set<String> allNames = new HashSet(query.keySet())
        for (String name in allNames) {
            if (query[name] == null) {
                query.remove name
                nullNames << name
            }
        }
        nullNames
    }

    static void enhanceProxyClass(Class proxyClass) {
        // don't do anything
    }
    
    static void enhanceProxy(HibernateProxy proxy) {
        // don't do anything
    }

    private static void registerNamespaceMethods(GrailsDomainClass dc, HibernateDatastore datastore,
            String datasourceName, PlatformTransactionManager  transactionManager,
            GrailsApplication application) {

        String getter = GrailsNameUtils.getGetterName(datasourceName)
        if (dc.metaClass.methods.any { MetaMethod it -> it.name == getter && it.parameterTypes.size() == 0 }) {
            LOG.warn "The $dc.clazz.name domain class has a method '$getter' - unable to add namespaced methods for datasource '$datasourceName'"
            return
        }

        def classLoader = application.classLoader

        def finders = HibernateGormEnhancer.createPersistentMethods(application, classLoader, datastore)
        def staticApi = new HibernateGormStaticApi(dc.clazz, datastore, finders, classLoader, transactionManager)
        ((GroovyObject)((GroovyObject)dc.metaClass).getProperty('static')).setProperty(getter, { -> staticApi })

        def validateApi = new HibernateGormValidationApi(dc.clazz, datastore, classLoader)
        def instanceApi = new HibernateGormInstanceApi(dc.clazz, datastore, classLoader)
        ((GroovyObject)dc.metaClass).setProperty(getter, { -> new InstanceProxy(getDelegate(), instanceApi, validateApi) })
    }

    /**
     * Session should no longer be flushed after a data access exception occurs (such a constriant violation)
     */
    static void handleDataAccessException(HibernateTemplate template, DataAccessException e) {
        try {
            template.execute({Session session ->
                session.setFlushMode(FlushMode.MANUAL)
            } as HibernateCallback)
        }
        finally {
            throw e
        }
    }

    static shouldFlush(GrailsApplication application, Map map = [:]) {
        def shouldFlush

        if (map?.containsKey('flush')) {
            shouldFlush = Boolean.TRUE == map.flush
        } else {
            def config = application.flatConfig
            shouldFlush = Boolean.TRUE == config.get('grails.gorm.autoFlush')
        }
        return shouldFlush
    }

    /**
     * Converts an id value to the appropriate type for a domain class.
     *
     * @param grailsDomainClass a GrailsDomainClass
     * @param idValue an value to be converted
     * @return the idValue parameter converted to the type that grailsDomainClass expects
     * its identifiers to be
     */
    static Object convertValueToIdentifierType(GrailsDomainClass grailsDomainClass, Object idValue) {
        convertValueToType(idValue, grailsDomainClass.identifier.type)
    }
    
    static Object convertValueToType(Object passedValue, Class targetType) {
        // workaround for GROOVY-6127, do not assign directly in parameters before it's fixed
        Object value = passedValue
        if(targetType != null && value != null && !(value in targetType)) {
            if (value instanceof CharSequence) {
                value = value.toString()
                if(value in targetType) {
                    return value
                }
            }
            try {
                if (value instanceof Number && (targetType==Long || targetType==Integer)) {
                    if(targetType == Long) {
                        value = ((Number)value).toLong()
                    } else {
                        value = ((Number)value).toInteger()
                    }
                } else if (value instanceof String && targetType in Number) {
                    String strValue = value.trim()
                    if(targetType == Long) {
                        value = Long.parseLong(strValue)
                    } else if (targetType == Integer) {
                        value = Integer.parseInt(strValue)
                    } else {
                        value = StringGroovyMethods.asType(strValue, targetType)
                    }
                } else {
                    value = new SimpleTypeConverter().convertIfNecessary(value, targetType)
                }
            } catch (e) {
                // ignore
            }
        }
        return value
    }
}
