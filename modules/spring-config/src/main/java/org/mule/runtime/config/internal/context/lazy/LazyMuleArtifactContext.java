/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.context.lazy;

import static org.mule.runtime.api.component.TypedComponentIdentifier.ComponentType.OPERATION;
import static org.mule.runtime.api.component.TypedComponentIdentifier.ComponentType.SCOPE;
import static org.mule.runtime.api.component.TypedComponentIdentifier.ComponentType.SOURCE;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.util.Preconditions.checkState;
import static org.mule.runtime.ast.api.util.MuleAstUtils.resolveOrphanComponents;
import static org.mule.runtime.ast.graph.api.ArtifactAstDependencyGraphFactory.generateFor;
import static org.mule.runtime.config.internal.parsers.generic.AutoIdUtils.uniqueValue;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_LAZY_INIT_ENABLE_DSL_DECLARATION_VALIDATIONS_DEPLOYMENT_PROPERTY;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_SECURITY_MANAGER;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.core.privileged.registry.LegacyRegistryUtils.unregisterObject;
import static org.mule.runtime.extension.api.stereotype.MuleStereotypes.APP_CONFIG;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.sort;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import static com.google.common.collect.ImmutableSet.copyOf;
import static com.google.common.collect.Sets.newHashSet;

import org.mule.runtime.api.component.ConfigurationProperties;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.config.FeatureFlaggingService;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.i18n.I18nMessageFactory;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.memory.management.MemoryManagementService;
import org.mule.runtime.api.meta.model.stereotype.HasStereotypeModel;
import org.mule.runtime.api.metadata.ExpressionLanguageMetadataService;
import org.mule.runtime.api.util.Pair;
import org.mule.runtime.ast.api.ArtifactAst;
import org.mule.runtime.ast.api.ComponentAst;
import org.mule.runtime.ast.graph.api.ArtifactAstDependencyGraph;
import org.mule.runtime.config.internal.context.BaseConfigurationComponentLocator;
import org.mule.runtime.config.internal.context.MuleArtifactContext;
import org.mule.runtime.config.internal.context.SpringConfigurationComponentLocator;
import org.mule.runtime.config.internal.context.SpringMuleContextServiceConfigurator;
import org.mule.runtime.config.internal.dsl.model.NoSuchComponentModelException;
import org.mule.runtime.config.internal.dsl.model.SpringComponentModel;
import org.mule.runtime.config.internal.model.ComponentBuildingDefinitionRegistryFactory;
import org.mule.runtime.config.internal.model.ComponentModelInitializer;
import org.mule.runtime.config.internal.registry.OptionalObjectsController;
import org.mule.runtime.config.internal.validation.IgnoreOnLazyInit;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.bootstrap.ArtifactType;
import org.mule.runtime.core.api.security.SecurityManager;
import org.mule.runtime.core.api.transaction.TransactionManagerFactory;
import org.mule.runtime.core.internal.exception.ContributedErrorTypeLocator;
import org.mule.runtime.core.internal.exception.ContributedErrorTypeRepository;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChain;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChainBuilder;
import org.mule.runtime.core.privileged.registry.RegistrationException;
import org.mule.runtime.extension.api.runtime.config.ConfigurationProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Implementation of {@link MuleArtifactContext} that allows to create configuration components lazily.
 * <p/>
 * Components will be created upon request to use the from the exposed services.
 *
 * @since 4.0
 */
public class LazyMuleArtifactContext extends MuleArtifactContext
    implements LazyComponentInitializerAdapter, ComponentModelInitializer {

  public static final String SHARED_PARTITIONED_PERSISTENT_OBJECT_STORE_PATH = "_sharedPartitionatedPersistentObjectStorePath";

  private static final Logger LOGGER = LoggerFactory.getLogger(LazyMuleArtifactContext.class);

  private final boolean dslDeclarationValidationEnabled;

  private TrackingPostProcessor trackingPostProcessor;

  private final Optional<ComponentModelInitializer> parentComponentModelInitializer;

  private final ArtifactAstDependencyGraph graph;

  private final Set<String> currentComponentLocationsRequested = new HashSet<>();
  private boolean appliedStartedPhaseRequest = false;

  private final Map<String, String> artifactProperties;
  private final LockFactory runtimeLockFactory;

  /**
   * Parses configuration files creating a spring ApplicationContext which is used as a parent registry using the SpringRegistry
   * registry implementation to wraps the spring ApplicationContext
   *
   * @param muleContext                                the {@link MuleContext} that own this context
   * @param artifactAst                                the definition of the artifact to create a context for
   * @param optionalObjectsController                  the {@link OptionalObjectsController} to use. Cannot be {@code null} @see
   *                                                   org.mule.runtime.config.internal.SpringRegistry
   * @param parentConfigurationProperties              the resolver for properties from the parent artifact to be used as fallback
   *                                                   in this artifact.
   * @param baseConfigurationComponentLocator          indirection to the actual ConfigurationComponentLocator in the full
   *                                                   registry
   * @param errorTypeRepository                        repository where the errors of the artifact will be registered.
   * @param errorTypeLocator                           locator where the errors of the artifact will be registered.
   * @param artifactProperties                         map of properties that can be referenced from the
   *                                                   {@code artifactConfigResources} as external configuration values
   * @param artifactType                               the type of artifact to determine the base objects of the created context.
   * @param parentComponentModelInitializer
   * @param runtimeLockFactory
   * @param componentBuildingDefinitionRegistryFactory
   * @param featureFlaggingService
   * @since 4.0
   */
  public LazyMuleArtifactContext(MuleContext muleContext, ArtifactAst artifactAst,
                                 OptionalObjectsController optionalObjectsController,
                                 Optional<ConfigurationProperties> parentConfigurationProperties,
                                 BaseConfigurationComponentLocator baseConfigurationComponentLocator,
                                 ContributedErrorTypeRepository errorTypeRepository,
                                 ContributedErrorTypeLocator errorTypeLocator,
                                 Map<String, String> artifactProperties, ArtifactType artifactType,
                                 Optional<ComponentModelInitializer> parentComponentModelInitializer,
                                 LockFactory runtimeLockFactory,
                                 ComponentBuildingDefinitionRegistryFactory componentBuildingDefinitionRegistryFactory,
                                 MemoryManagementService memoryManagementService,
                                 FeatureFlaggingService featureFlaggingService,
                                 ExpressionLanguageMetadataService expressionLanguageMetadataService)
      throws BeansException {
    super(muleContext, artifactAst, optionalObjectsController, parentConfigurationProperties,
          baseConfigurationComponentLocator, errorTypeRepository, errorTypeLocator,
          artifactProperties, artifactType, componentBuildingDefinitionRegistryFactory, memoryManagementService,
          featureFlaggingService, expressionLanguageMetadataService);

    // Changes the component locator in order to allow accessing any component by location even when they are prototype
    this.componentLocator = new SpringConfigurationComponentLocator();

    this.parentComponentModelInitializer = parentComponentModelInitializer;

    this.dslDeclarationValidationEnabled = Boolean.valueOf(artifactProperties
        .getOrDefault(MULE_LAZY_INIT_ENABLE_DSL_DECLARATION_VALIDATIONS_DEPLOYMENT_PROPERTY, Boolean.FALSE.toString()));

    this.artifactProperties = artifactProperties;
    this.runtimeLockFactory = runtimeLockFactory;

    initialize();
    // Graph should be generated after the initialize() method since the applicationModel will change by macro expanding XmlSdk
    // components.
    this.graph = generateFor(getApplicationModel());
  }

  @Override
  protected SpringMuleContextServiceConfigurator createServiceConfigurator(DefaultListableBeanFactory beanFactory) {
    return new LazySpringMuleContextServiceConfigurator(this,
                                                        artifactProperties,
                                                        runtimeLockFactory,
                                                        getMuleContext(),
                                                        getCoreFunctionsProvider(),
                                                        getConfigurationProperties(),
                                                        getArtifactType(),
                                                        getApplicationModel(),
                                                        getOptionalObjectsController(),
                                                        beanFactory,
                                                        getServiceDiscoverer(),
                                                        getResourceLocator(),
                                                        memoryManagementService);
  }

  @Override
  protected void validateArtifact(ArtifactAst artifactAst) {
    // Nothing to do, validation is done after calculating the minimal artifact in #createComponents
  }

  @Override
  protected void registerErrors(ArtifactAst artifactAst) {
    // Nothing to do, errorType repository is done after calculating the minimal artifact in #createComponents
  }

  @Override
  protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    super.prepareBeanFactory(beanFactory);
    trackingPostProcessor = new TrackingPostProcessor();
    addBeanPostProcessors(beanFactory, trackingPostProcessor);
  }

  private void applyLifecycle(List<Object> components, boolean applyStartPhase) {
    getMuleContext().withLifecycleLock(() -> {
      if (getMuleContext().isInitialised()) {
        initializeComponents(components);
      }
      if (getMuleContext().isStarted()) {
        if (applyStartPhase) {
          startComponent(components);
        } else {
          startConfigurationProviders(components);
        }
      }
    });
  }

  /**
   * Starts {@link ConfigurationProvider} components as they should be started no matter if the request has set to not apply start
   * phase in the rest of the components.
   *
   * @param components list of components created
   */
  private void startConfigurationProviders(List<Object> components) {
    components.stream()
        .filter(component -> component instanceof ConfigurationProvider)
        .forEach(configurationProviders -> {
          try {
            getMuleRegistry().applyLifecycle(configurationProviders, Initialisable.PHASE_NAME, Startable.PHASE_NAME);
          } catch (MuleException e) {
            throw new MuleRuntimeException(e);
          }
        });
  }

  private void initializeComponents(List<Object> components) {
    for (Object object : components) {
      LOGGER.debug("Initializing component '{}'...", object.toString());
      try {
        if (object instanceof MessageProcessorChain) {
          // When created it will be initialized
        } else {
          getMuleRegistry().applyLifecycle(object, Initialisable.PHASE_NAME);
        }
      } catch (MuleException e) {
        throw new MuleRuntimeException(e);
      }
    }
  }

  private void startComponent(List<Object> components) {
    for (Object object : components) {
      LOGGER.debug("Starting component '{}'...", object.toString());
      try {
        if (object instanceof MessageProcessorChain) {
          // Has to be ignored as when it is registered it will be started too
        } else {
          getMuleRegistry().applyLifecycle(object, Initialisable.PHASE_NAME, Startable.PHASE_NAME);
        }
      } catch (MuleException e) {
        throw new MuleRuntimeException(e);
      }
    }
  }

  @Override
  public void initializeComponent(Location location) {
    initializeComponent(location, true);
  }

  @Override
  public void initializeComponents(ComponentLocationFilter filter) {
    initializeComponents(filter, true);
  }

  @Override
  public void initializeComponent(Location location, boolean applyStartPhase) {
    applyLifecycle(createComponents(empty(), of(location), applyStartPhase,
                                    getParentComponentModelInitializerAdapter(applyStartPhase)),
                   applyStartPhase);
  }

  @Override
  public void initializeComponents(ComponentLocationFilter filter, boolean applyStartPhase) {
    applyLifecycle(createComponents(of(componentModel -> {
      if (componentModel.getLocation() != null) {
        return filter.accept(componentModel.getLocation());
      }
      return false;
    }), empty(), applyStartPhase, getParentComponentModelInitializerAdapter(applyStartPhase)), applyStartPhase);
  }

  @Override
  public void initializeComponents(Predicate<ComponentAst> componentModelPredicate,
                                   boolean applyStartPhase) {
    applyLifecycle(createComponents(of(componentModelPredicate), empty(), applyStartPhase,
                                    getParentComponentModelInitializerAdapter(applyStartPhase)),
                   applyStartPhase);
  }

  public Optional<ComponentModelInitializerAdapter> getParentComponentModelInitializerAdapter(boolean applyStartPhase) {
    return parentComponentModelInitializer
        .map(componentModelInitializer -> componentModelPredicate -> componentModelInitializer
            .initializeComponents(componentModelPredicate, applyStartPhase));
  }

  private List<Object> createComponents(Optional<Predicate<ComponentAst>> predicateOptional, Optional<Location> locationOptional,
                                        boolean applyStartPhase,
                                        Optional<ComponentModelInitializerAdapter> parentComponentModelInitializerAdapter) {
    checkState(predicateOptional.isPresent() != locationOptional.isPresent(), "predicate or location has to be passed");
    return withContextClassLoader(getMuleContext().getExecutionClassLoader(), () -> {
      // User input components to be initialized...
      final Predicate<ComponentAst> basePredicate =
          predicateOptional.orElseGet(() -> comp -> comp.getLocation() != null
              && comp.getLocation().getLocation().equals(locationOptional.get().toString()));

      final ArtifactAst minimalApplicationModel = buildMinimalApplicationModel(basePredicate);

      if (dslDeclarationValidationEnabled) {
        doValidateModel(minimalApplicationModel, v -> v.getClass().getAnnotation(IgnoreOnLazyInit.class) == null
            || v.getClass().getAnnotation(IgnoreOnLazyInit.class).forceDslDeclarationValidation());
      } else {
        doValidateModel(minimalApplicationModel, v -> v.getClass().getAnnotation(IgnoreOnLazyInit.class) == null);
      }

      if (locationOptional.map(loc -> minimalApplicationModel.recursiveStream()
          .noneMatch(comp -> comp.getLocation() != null
              && comp.getLocation().getLocation().equals(loc.toString())))
          .orElse(false)) {
        throw new NoSuchComponentModelException(createStaticMessage("No object found at location "
            + locationOptional.get().toString()));
      }

      Set<String> requestedLocations = locationOptional.map(location -> (Set<String>) newHashSet(location.toString()))
          .orElseGet(() -> getApplicationModel()
              .filteredComponents(basePredicate)
              .map(comp -> comp.getLocation().getLocation())
              .collect(toSet()));

      if (copyOf(currentComponentLocationsRequested).equals(copyOf(requestedLocations)) &&
          appliedStartedPhaseRequest == applyStartPhase) {
        // Same minimalApplication has been requested, so we don't need to recreate the same beans.
        return emptyList();
      }

      if (parentComponentModelInitializerAdapter.isPresent()) {
        parentComponentModelInitializerAdapter.get()
            .initializeComponents(componentModel -> graph.getMissingDependencies()
                .stream()
                .anyMatch(missingDep -> missingDep.isSatisfiedBy(componentModel)));
      } else {
        graph.getMissingDependencies().stream().forEach(missingDep -> {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Ignoring dependency {} because it does not exist.", missingDep);
          }
        });
      }

      // First unregister any already initialized/started component
      unregisterBeans(trackingPostProcessor.getBeansTracked());

      currentComponentLocationsRequested.clear();
      currentComponentLocationsRequested.addAll(requestedLocations);
      appliedStartedPhaseRequest = applyStartPhase;

      // Clean up resources...
      trackingPostProcessor.reset();
      objectProviders.clear();
      resetMuleSecurityManager();

      // This has to be called after all previous state has been cleared because the unregister/cleanup process requires the
      // errorTypeRespository as it was during its initialization.
      doRegisterErrors(minimalApplicationModel);

      List<Pair<String, ComponentAst>> applicationComponents =
          createApplicationComponents((DefaultListableBeanFactory) this.getBeanFactory(), minimalApplicationModel, false);

      super.prepareObjectProviders();

      LOGGER.debug("Will create beans: {}", applicationComponents);
      return createBeans(applicationComponents);
    });
  }

  private ArtifactAst buildMinimalApplicationModel(final Predicate<ComponentAst> basePredicate) {
    return graph.minimalArtifactFor(basePredicate
        .or(cm -> cm.getModel(HasStereotypeModel.class)
            .map(stm -> stm.getStereotype() != null && stm.getStereotype().isAssignableTo(APP_CONFIG))
            .orElse(false)));
  }

  /**
   * Apart from calling {@link #createApplicationComponents(DefaultListableBeanFactory, ArtifactAst, boolean)} from the
   * superclass, will handle orphan processors. That is, processors that are part of the minimal app but for which the containing
   * flow is not.
   */
  @Override
  protected List<Pair<String, ComponentAst>> doCreateApplicationComponents(DefaultListableBeanFactory beanFactory,
                                                                           ArtifactAst minimalAppModel,
                                                                           boolean mustBeRoot,
                                                                           Map<ComponentAst, SpringComponentModel> springComponentModels) {
    final List<Pair<String, ComponentAst>> applicationComponents =
        super.doCreateApplicationComponents(beanFactory, minimalAppModel, mustBeRoot, springComponentModels);

    final Set<ComponentAst> orphanComponents = resolveOrphanComponents(minimalAppModel);
    LOGGER.debug("orphanComponents found: {}", orphanComponents.toString());

    // Handle orphan named components...
    orphanComponents.stream()
        .filter(cm -> asList(SOURCE, OPERATION, SCOPE).contains(cm.getComponentType()))
        .filter(cm -> cm.getComponentId().isPresent())
        .forEach(cm -> {
          final String nameAttribute = cm.getComponentId().get();
          LOGGER.debug("Registering orphan named component '{}'...", nameAttribute);

          applicationComponents.add(0, new Pair<>(nameAttribute, cm));
          final SpringComponentModel springCompModel = springComponentModels.get(cm);
          final BeanDefinition beanDef = springCompModel.getBeanDefinition();
          if (beanDef != null) {
            beanFactory.registerBeanDefinition(cm.getComponentId().get(), beanDef);
            postProcessBeanDefinition(springCompModel, beanFactory, cm.getComponentId().get());
          }
        });

    // Handle orphan components without name, rely on the location.
    orphanComponents.stream()
        .forEach(cm -> {
          final SpringComponentModel springCompModel = springComponentModels.get(cm);
          final BeanDefinition beanDef = springCompModel.getBeanDefinition();
          if (beanDef != null) {
            final String beanName = cm.getComponentId().orElse(uniqueValue(beanDef.getBeanClassName()));

            LOGGER.debug("Registering orphan un-named component '{}'...", beanName);
            applicationComponents.add(new Pair<>(beanName, cm));
            beanFactory.registerBeanDefinition(beanName, beanDef);
            postProcessBeanDefinition(springCompModel, beanFactory, beanName);
          }
        });

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("applicationComponents to be created: {}", applicationComponents.toString());
    }
    return applicationComponents;
  }

  /**
   * Creates the beans based on the application component model names that were enabled by the minimal application model. It also
   * populates the list of bean names created and returns the list of beans instantiated, the list of beans is sorted based on
   * dependencies between components (even between configuration components, flow->config and config->config dependencies from the
   * DSL).
   *
   * @param applicationComponentNames name of components to be created.
   * @return List beans created for the given component names sorted by precedence.
   */
  private List<Object> createBeans(List<Pair<String, ComponentAst>> applicationComponentNames) {
    trackingPostProcessor.startTracking();
    Map<Pair<String, ComponentAst>, Object> objects = new LinkedHashMap<>();
    // Create beans only once by calling the lookUp at the Registry
    applicationComponentNames.forEach(componentPair -> {
      try {
        Object object = getRegistry().lookupByName(componentPair.getFirst()).orElse(null);
        if (object != null) {
          // MessageProcessorChainBuilder has to be manually created and added to the registry in order to be able
          // to dispose it later
          if (object instanceof MessageProcessorChainBuilder) {
            handleChainBuilder((MessageProcessorChainBuilder) object, componentPair, objects);
          } else if (object instanceof TransactionManagerFactory) {
            handleTxManagerFactory((TransactionManagerFactory) object);
          }
          objects.put(componentPair, object);
        }
      } catch (Exception e) {
        trackingPostProcessor.stopTracking();
        trackingPostProcessor.intersection(objects.keySet().stream().map(pair -> pair.getFirst()).collect(toList()));
        safeUnregisterBean(componentPair.getFirst());

        throw new MuleRuntimeException(e);
      }
    });

    // A Map to access the componentName by the bean instance
    Map<Object, Pair<String, ComponentAst>> componentNames = new HashMap<>();
    objects.entrySet().forEach(entry -> {
      Object object = entry.getValue();
      Pair<String, ComponentAst> component = entry.getKey();
      componentNames.put(object, component);
    });

    // TODO: Once is implemented MULE-17778 we should use graph to get the order for disposing beans
    trackingPostProcessor.stopTracking();
    trackingPostProcessor.intersection(objects.keySet().stream().map(pair -> pair.getFirst()).collect(toList()));

    // Sort in order to later initialize and start components according to their dependencies
    List<Object> sortedObjects = new ArrayList<>(objects.values());
    sort(sortedObjects, (o1, o2) -> graph.dependencyComparator().compare(componentNames.get(o1).getSecond(),
                                                                         componentNames.get(o2).getSecond()));
    return sortedObjects;
  }

  private void handleChainBuilder(MessageProcessorChainBuilder object, Pair<String, ComponentAst> componentPair,
                                  Map<Pair<String, ComponentAst>, Object> objects) {
    Pair<String, ComponentAst> chainKey =
        new Pair<>(componentPair.getFirst() + "@" + object.hashCode(), componentPair.getSecond());
    MessageProcessorChain messageProcessorChain = object.build();
    try {
      initialiseIfNeeded(messageProcessorChain, getMuleContext());
    } catch (InitialisationException e) {
      unregisterBeans(objects.keySet().stream().map(p -> p.getFirst()).collect(toList()));
      throw new IllegalStateException("Couldn't initialise an instance of a MessageProcessorChain", e);
    }
    try {
      getMuleRegistry().registerObject(chainKey.getFirst(), messageProcessorChain);
    } catch (RegistrationException e) {
      // Unregister any already created component
      unregisterBeans(objects.keySet().stream().map(p -> p.getFirst()).collect(toList()));
      throw new IllegalStateException("Couldn't register an instance of a MessageProcessorChain", e);
    }
    objects.put(chainKey, messageProcessorChain);
  }

  private void handleTxManagerFactory(TransactionManagerFactory object) {
    try {
      getMuleContext().setTransactionManager(object.create(getMuleContext().getConfiguration()));
    } catch (Exception e) {
      throw new IllegalStateException("Couldn't register an instance of a TransactionManager", e);
    }
  }

  private void resetMuleSecurityManager() {
    SecurityManager securityManager = getMuleRegistry().get(OBJECT_SECURITY_MANAGER);

    if (securityManager != null) {
      securityManager.getProviders().forEach(p -> securityManager.removeProvider(p.getName()));
      securityManager.getEncryptionStrategies().forEach(s -> securityManager.removeEncryptionStrategy(s.getName()));
    }
  }

  @Override
  protected void prepareObjectProviders() {
    // Do not prepare object providers at this point. No components are going to be created yet. This will be done when creating
    // lazy components
  }

  @Override
  public void close() {
    if (trackingPostProcessor != null) {
      trackingPostProcessor.stopTracking();
      trackingPostProcessor.reset();
    }

    appliedStartedPhaseRequest = false;
    currentComponentLocationsRequested.clear();

    super.close();
  }

  private void unregisterBeans(List<String> beans) {
    doUnregisterBeans(beans.stream()
        .collect(toCollection(LinkedList::new)).descendingIterator());
    componentLocator.removeComponents();
  }

  /**
   * Apply the stop and dispose phases and unregister the bean from the registry. The phases are applied to each bean at a time.
   *
   * @param beanNames {@link Iterator} of bean names to be stopped, disposed and unregistered.
   */
  private void doUnregisterBeans(Iterator<String> beanNames) {
    while (beanNames.hasNext()) {
      String beanName = beanNames.next();
      try {
        unregisterObject(getMuleContext(), beanName);
      } catch (Exception e) {
        logger.error(String
            .format("Exception unregistering an object during lazy initialization of component %s, exception message is %s",
                    beanName, e.getMessage()));
        throw new MuleRuntimeException(I18nMessageFactory
            .createStaticMessage("There was an error while unregistering component '%s'", beanName), e);
      }
    }
  }

  private void safeUnregisterBean(String beanName) {
    try {
      unregisterObject(getMuleContext(), beanName);
    } catch (RegistrationException e) {
      // Nothing to do...
    }
  }

  /*
   * Just register the locations, do not do any initialization!
   */
  @Override
  protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) {
    getApplicationModel()
        .filteredComponents(cm -> !isIgnored(cm))
        .forEach(cm -> componentLocator.addComponentLocation(cm.getLocation()));
  }

  /**
   * Adapter for {@link ComponentModelInitializer} that hides the lifecycle phase from component model creation logic.
   */
  @FunctionalInterface
  private interface ComponentModelInitializerAdapter {

    void initializeComponents(Predicate<ComponentAst> componentModelPredicate);

  }

}
