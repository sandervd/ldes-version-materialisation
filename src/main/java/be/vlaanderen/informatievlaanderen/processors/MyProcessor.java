/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package be.vlaanderen.informatievlaanderen.processors;

import org.apache.commons.io.IOUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.impl.TreeModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.*;

@Tags({"ldes, vsds"})
@CapabilityDescription("Version materialisation of an LDES stream")
public class MyProcessor extends AbstractProcessor {

    public static final ValueFactory vf = SimpleValueFactory.getInstance();
    public static final PropertyDescriptor IS_VERSION_OF = new PropertyDescriptor
            .Builder().name("isVersionOf")
            .displayName("Predicate used for isVersionOf")
            .required(true)
            .defaultValue("http://purl.org/dc/terms/isVersionOf")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .addValidator(StandardValidators.URI_VALIDATOR)
            .build();

    public static final Relationship MY_RELATIONSHIP = new Relationship.Builder()
            .name("MY_RELATIONSHIP")
            .description("Example relationship")
            .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        descriptors = new ArrayList<>();
        descriptors.add(IS_VERSION_OF);
        descriptors = Collections.unmodifiableList(descriptors);

        relationships = new HashSet<>();
        relationships.add(MY_RELATIONSHIP);
        relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {

    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        FlowFile flowFile = session.get();
        IRI isVersionOf = vf.createIRI(context.getProperty(IS_VERSION_OF).getValue());

        if ( flowFile == null ) {
            return;
        }
        StringWriter outputStream = new StringWriter();
        session.read(flowFile, new InputStreamCallback() {
            @Override
            public void process(InputStream in) throws IOException {
                String FragmentRDF = IOUtils.toString(in);
                getLogger().warn(String.format("Got the following RDF: %s", FragmentRDF));
                InputStream targetStream = IOUtils.toInputStream(FragmentRDF);
                Model inputModel = Rio.parse(targetStream, "", RDFFormat.NQUADS);

                Model versionMaterialisedModel = versionMaterialise(inputModel, isVersionOf);

                Model outModel = reduceToLDESMemberOnlyModel(versionMaterialisedModel);
                Rio.write(outModel, outputStream, RDFFormat.TURTLE);
            }
        });
        
        flowFile = session.write(flowFile, out -> out.write(outputStream.toString().getBytes()));
        session.transfer(flowFile, MY_RELATIONSHIP);
    }
    public static Model versionMaterialise(Model inputModel, IRI versionPredicate) {
        Model versionMaterialisedModel = new TreeModel();

        Map<Resource, IRI> versionIDEntityIDMap = getVersionIDEntityIDMap(inputModel, versionPredicate);

        inputModel.forEach(statement -> {
            Resource subject = statement.getSubject();
            IRI predicate = statement.getPredicate();
            Value object = statement.getObject();

            // Statement needs 'de-versioning', replacing the subject.
            if (versionIDEntityIDMap.containsKey(statement.getSubject()))
                subject = versionIDEntityIDMap.get(statement.getSubject());

            // Object references a versioned entity, replace it with the 'de-versioned' identifier.
            if (statement.getObject().isResource() && versionIDEntityIDMap.containsKey((Resource) statement.getObject()))
                object = versionIDEntityIDMap.get((Resource) statement.getObject());

            // Don't add isVersionOf statements.
            if (statement.getPredicate().equals(IS_VERSION_OF))
                return;

        /*
        @todo According to https://github.com/TREEcg/version-materialize-rdf.js "created" statements should be moved
        to a version object, referenced by hasVersion. Since I can't come up with a reason why this would be useful,
        or preferred over having the created statement in the entity itself, I'm not looking into this for now...
        */
            versionMaterialisedModel.add(vf.createStatement(subject, predicate, object));
        });
        return versionMaterialisedModel;
    }
    private static Map<Resource, IRI> getVersionIDEntityIDMap(Model model, IRI isVersionOfPredicate) {
        Map<Resource, IRI> map = new HashMap<>();
        model.getStatements(null, isVersionOfPredicate, null).forEach(memberStatement -> {
            if (!memberStatement.getObject().isResource()) {
                throw new RuntimeException(String.format(
                        "Statement <subject: %s predicate: %s> should have object identifier as object.",
                        memberStatement.getSubject().toString(),
                        memberStatement.getPredicate().toString()
                ));
            }
            map.put(memberStatement.getSubject(), (IRI) memberStatement.getObject());
        });
        return map;
    }

    /**
     * Builds a model limited to statements about the ldes:member, including potential nested blank nodes.
     * Excludes statements about referenced entities, provided as context.
     *
     * @param inputModel The model to reduce.
     * @return The reduced model.
     */
    private static Model reduceToLDESMemberOnlyModel(Model inputModel) {
        Stack<Resource> subjectsOfIncludedStatements = new Stack<>();
        Model LDESMemberModel = new TreeModel();

        // LDES Member statements
        inputModel.getStatements(null, Tree.MEMBER, null).forEach(memberStatement -> {
            // @todo Include statement that the ldes:member belongs to the stream?
            //outModel.add(memberStatement);
            subjectsOfIncludedStatements.push((Resource) memberStatement.getObject());
        });

        /*
         * LDES members can contain blank node references. All statements of those blank nodes
         * need to be included in the output as well. Blank nodes can be nested,
         * hence the need to keep track of them with a stack.
         */
        while (!subjectsOfIncludedStatements.isEmpty()) {
            Resource subject = subjectsOfIncludedStatements.pop();
            inputModel.getStatements(subject, null, null).forEach((Statement includedStatement) -> {
                LDESMemberModel.add(includedStatement);
                Value object = includedStatement.getObject();
                if (object.isBNode()) {
                    subjectsOfIncludedStatements.push((Resource) object);
                }
            });
        }
        return LDESMemberModel;
    }
}
