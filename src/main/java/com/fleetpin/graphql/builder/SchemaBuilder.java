/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.fleetpin.graphql.builder;

import com.fleetpin.graphql.builder.annotations.*;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import jakarta.validation.constraints.Size;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SchemaBuilder {

	private final DirectivesSchema directives;
	private final AuthorizerSchema authorizer;
	private final EntityProcessor entityProcessor;

	private SchemaBuilder(DataFetcherRunner dataFetcherRunner, List<GraphQLScalarType> scalars, DirectivesSchema directives, AuthorizerSchema authorizer) {
		this.directives = directives;
		this.authorizer = authorizer;

		this.entityProcessor = new EntityProcessor(dataFetcherRunner, scalars, directives);

		directives.processDirectives(entityProcessor);
	}

	private SchemaBuilder processTypes(Set<Class<?>> types) {
		for (var type : types) {
			TypeMeta meta = new TypeMeta(null, type, type);

			var annotation = type.getAnnotation(Entity.class);
			if (annotation.value() != SchemaOption.INPUT) {
				this.entityProcessor.getEntity(meta).getInnerType(meta);
			}
		}
		return this;
	}

	private SchemaBuilder process(HashSet<Method> endPoints) throws ReflectiveOperationException {
		var methodProcessor = this.entityProcessor.getMethodProcessor();
		for (var method : endPoints) {
			methodProcessor.process(authorizer, method);
		}

		return this;
	}

	private graphql.schema.GraphQLSchema.Builder build(Set<Class<? extends SchemaConfiguration>> schemaConfiguration) {
		var methods = entityProcessor.getMethodProcessor();

		var builder = GraphQLSchema.newSchema().codeRegistry(methods.getCodeRegistry().build()).additionalTypes(entityProcessor.getAdditionalTypes());

		var query = methods.getGraphQuery().build();
		builder.query(query);

		var mutations = methods.getGraphMutations().build();
		if (!mutations.getFields().isEmpty()) {
			builder.mutation(mutations);
		}
		var subscriptions = methods.getGraphSubscriptions().build();
		if (!subscriptions.getFields().isEmpty()) {
			builder.subscription(subscriptions);
		}

		directives.getSchemaDirective().forEach(directive -> builder.additionalDirective(directive));

		for (var schema : schemaConfiguration) {
			this.directives.addSchemaDirective(schema, schema, builder::withSchemaAppliedDirective);
		}
		return builder;
	}

	public static GraphQLSchema build(String... classPath) {
		return builder(classPath).build();
	}

	public static GraphQLSchema.Builder builder(String... classpath) {
		var builder = builder();
		for (var path : classpath) {
			builder.classpath(path);
		}
		return builder.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private DataFetcherRunner dataFetcherRunner = (method, fetcher) -> fetcher;
		private List<String> classpaths = new ArrayList<>();
		private List<GraphQLScalarType> scalars = new ArrayList<>();

		private Builder() {}

		public Builder dataFetcherRunner(DataFetcherRunner dataFetcherRunner) {
			this.dataFetcherRunner = dataFetcherRunner;
			return this;
		}

		public Builder classpath(String classpath) {
			this.classpaths.add(classpath);
			return this;
		}

		public Builder scalar(GraphQLScalarType scalar) {
			this.scalars.add(scalar);
			return this;
		}

		public GraphQLSchema.Builder build() {
			try {
				Reflections reflections = new Reflections(classpaths, Scanners.SubTypes, Scanners.MethodsAnnotated, Scanners.TypesAnnotated);
				Set<Class<? extends Authorizer>> authorizers = reflections.getSubTypesOf(Authorizer.class);
				//want to make everything split by package
				AuthorizerSchema authorizer = AuthorizerSchema.build(dataFetcherRunner, new HashSet<>(classpaths), authorizers);

				Set<Class<? extends SchemaConfiguration>> schemaConfiguration = reflections.getSubTypesOf(SchemaConfiguration.class);

				DirectivesSchema directivesSchema = getDirectivesSchema(reflections);

				Set<Class<?>> types = reflections.getTypesAnnotatedWith(Entity.class);

				var mutations = reflections.getMethodsAnnotatedWith(Mutation.class);
				var subscriptions = reflections.getMethodsAnnotatedWith(Subscription.class);
				var queries = reflections.getMethodsAnnotatedWith(Query.class);

				var endPoints = new HashSet<>(mutations);
				endPoints.addAll(subscriptions);
				endPoints.addAll(queries);

				types.removeIf(t -> t.getDeclaredAnnotation(Entity.class) == null);
				types.removeIf(Class::isAnonymousClass);

				return new SchemaBuilder(dataFetcherRunner, scalars, directivesSchema, authorizer)
					.processTypes(types)
					.process(endPoints)
					.build(schemaConfiguration);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		}

		private static DirectivesSchema getDirectivesSchema(Reflections reflections) throws ReflectiveOperationException {
			Set<Class<?>> directivesTypes = reflections.getTypesAnnotatedWith(Directive.class);
			directivesTypes.addAll(reflections.getTypesAnnotatedWith(DataFetcherWrapper.class));

			addAllJakartaAnnotations(directivesTypes);

			List<RestrictTypeFactory<?>> globalRestricts = getGlobalRestricts(reflections);

			return DirectivesSchema.build(globalRestricts, directivesTypes); // Entry point for directives
		}

		private static void addAllJakartaAnnotations(Set<Class<?>> directivesTypes) {
			// make sure the Constraint directive is added to the schema
			directivesTypes.add(Size.class);
		}

		private static List<RestrictTypeFactory<?>> getGlobalRestricts(Reflections reflections)
			throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
			Set<Class<?>> restrict = reflections.getTypesAnnotatedWith(Restrict.class);
			Set<Class<?>> restricts = reflections.getTypesAnnotatedWith(Restricts.class);
			List<RestrictTypeFactory<?>> globalRestricts = new ArrayList<>();

			for (var r : restrict) {
				Restrict annotation = EntityUtil.getAnnotation(r, Restrict.class);
				var factoryClass = annotation.value();
				var factory = factoryClass.getConstructor().newInstance();
				if (!factory.extractType().isAssignableFrom(r)) {
					throw new RuntimeException(
						"Restrict annotation does match class applied to targets" + factory.extractType() + " but was on class " + r
					);
				}
				globalRestricts.add(factory);
			}

			for (var r : restricts) {
				Restricts annotations = EntityUtil.getAnnotation(r, Restricts.class);
				for (Restrict annotation : annotations.value()) {
					var factoryClass = annotation.value();
					var factory = factoryClass.getConstructor().newInstance();

					if (!factory.extractType().isAssignableFrom(r)) {
						throw new RuntimeException(
							"Restrict annotation does match class applied to targets" + factory.extractType() + " but was on class " + r
						);
					}
					globalRestricts.add(factory);
				}
			}
			return globalRestricts;
		}
	}
}
