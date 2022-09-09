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

import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;


public class VersionMaterialiseProcessorTest {

    public static final ValueFactory vf = SimpleValueFactory.getInstance();

    private TestRunner testRunner;

    @BeforeEach
    public void init() {
        testRunner = TestRunners.newTestRunner(VersionMaterialiseProcessor.class);
    }

    /**
     * Assert that a test file can be version materialised successfully.
     *
     * @throws IOException
     */
    @Test
    public void testVersionMaterialise() throws IOException {
        InputStream versionedInput = new FileInputStream("src/test/resources/ldes-member-versioned.ttl");
        Model VersionedModel = Rio.parse(versionedInput, "", RDFFormat.TURTLE);

        InputStream unVersionedInput = new FileInputStream("src/test/resources/ldes-member-unversioned.ttl");
        Model ComparisonModel = Rio.parse(unVersionedInput, "", RDFFormat.TURTLE);

        Model unversionedModel = VersionMaterialiser.versionMaterialise(VersionedModel, vf.createIRI("http://purl.org/dc/terms/isVersionOf"));
        Model membersOnlyModel = VersionMaterialiser.reduceToLDESMemberOnlyModel(unversionedModel);

        assert Models.isomorphic(membersOnlyModel, ComparisonModel);
    }

}
