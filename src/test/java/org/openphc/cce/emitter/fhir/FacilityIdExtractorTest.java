package org.openphc.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FacilityIdExtractorTest {

    private FacilityIdExtractor extractor;
    private FhirContext fhirContext;

    @BeforeEach
    void setUp() {
        extractor = new FacilityIdExtractor();
        fhirContext = FhirContext.forR4();
    }

    // ── Encounter ────────────────────────────────────────────────────────────

    @Nested
    class EncounterExtraction {

        @Test
        void extractsFromLocationPrefix() {
            Encounter encounter = new Encounter();
            encounter.addLocation().setLocation(new Reference("Location/0030"));

            assertThat(extractor.extract(encounter)).isEqualTo("0030");
        }

        @Test
        void extractsFromOrganizationPrefix() {
            Encounter encounter = new Encounter();
            encounter.addLocation().setLocation(new Reference("Organization/1302"));

            assertThat(extractor.extract(encounter)).isEqualTo("1302");
        }

        @Test
        void extractsFromIdentifierWhenNoReference() {
            Encounter encounter = new Encounter();
            Reference locationRef = new Reference();
            locationRef.setIdentifier(new Identifier().setValue("Kacyiru Health Center"));
            encounter.addLocation().setLocation(locationRef);

            assertThat(extractor.extract(encounter)).isEqualTo("Kacyiru Health Center");
        }

        @Test
        void prefersReferenceOverIdentifier() {
            Encounter encounter = new Encounter();
            Reference locationRef = new Reference("Location/0030");
            locationRef.setIdentifier(new Identifier().setValue("Kacyiru Health Center"));
            encounter.addLocation().setLocation(locationRef);

            assertThat(extractor.extract(encounter)).isEqualTo("0030");
        }

        @Test
        void returnsNullWhenLocationListEmpty() {
            assertThat(extractor.extract(new Encounter())).isNull();
        }

        @Test
        void extractsFromParsedPayload() {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "809cd034-f2d8-44d0-a95e-f42c44305afa",
                      "status": "in-progress",
                      "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB"},
                      "subject": {"reference": "Patient/240717-SITE-7293"},
                      "location": [{"location": {"reference": "Location/0030", "type": "Location",
                        "identifier": {"value": "Kacyiru Health Center"}, "display": "Kacyiru Health Center"}}]
                    }
                    """;
            IBaseResource resource = fhirContext.newJsonParser().parseResource(json);
            assertThat(extractor.extract(resource)).isEqualTo("0030");
        }

        @Test
        void prefersSourceFacilityExtensionOverLocation() {
            // Plain visit/consultation encounters: location and extension agree, so this is not
            // observable in practice, but the extension must still win per the documented order.
            Encounter encounter = new Encounter();
            encounter.addLocation().setLocation(new Reference("Location/0030"));
            encounter.addExtension(new Extension(
                    "http://example.org/fhir/StructureDefinition/source-facility", new StringType("0030")));

            assertThat(extractor.extract(encounter)).isEqualTo("0030");
        }

        @Test
        void fallsBackToLocationWhenNoSourceFacilityExtension() {
            Encounter encounter = new Encounter();
            encounter.addLocation().setLocation(new Reference("Location/0030"));

            assertThat(extractor.extract(encounter)).isEqualTo("0030");
        }

        @Test
        void transferEncounterUsesSourceFacilityNotDestinationLocation() {
            // Regression test for a real production incident (2026-07-17): a TRANSFER_ENCOUNTER's
            // location[0].location is the transfer DESTINATION, not the reporting facility, so
            // extracting from it compared a destination-facility UUID against FACILITY_FILTER_IDS
            // (short numeric codes) — which can never match, silently dropping every transfer-out
            // event regardless of whether the true reporting facility was allow-listed. The
            // source-facility extension (and hospitalization.origin) correctly carry "1651"; the
            // fix makes the extension take priority for Encounter so the filter checks the right ID.
            Encounter encounter = new Encounter();
            encounter.addLocation().setLocation(
                    new Reference("Location/d297cb62-4920-4c70-ba42-eedf32a69043")); // transfer destination (Ruli DH)
            encounter.addExtension(new Extension(
                    "http://example.org/fhir/StructureDefinition/source-facility", new StringType("1651")));

            assertThat(extractor.extract(encounter)).isEqualTo("1651");
        }

        @Test
        void extractsFromParsedTransferEncounterPayload() {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "89aeba57-f024-432b-bc2f-379402fe0232",
                      "status": "finished",
                      "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "EMER"},
                      "type": [{"coding": [{"display": "TRANSFER_ENCOUNTER"}]}],
                      "subject": {"reference": "Patient/260227-1651-7600"},
                      "hospitalization": {
                        "origin": {"reference": "Location/1651",
                          "identifier": {"system": "http://fhir.openmrs.org/ext/fosa-code", "value": "1651"},
                          "display": "Minazi Health Center"},
                        "destination": {"reference": "Location/d297cb62-4920-4c70-ba42-eedf32a69043",
                          "identifier": {"system": "http://fhir.openmrs.org/ext/fosa-code", "value": "0302"},
                          "display": "Ruli DH"}
                      },
                      "location": [{"location": {"reference": "Location/d297cb62-4920-4c70-ba42-eedf32a69043",
                        "display": "Ruli DH"}, "status": "completed"}],
                      "extension": [
                        {"url": "http://example.org/fhir/StructureDefinition/source-system", "valueString": "eBuzima"},
                        {"url": "http://example.org/fhir/StructureDefinition/source-facility", "valueString": "1651"}
                      ]
                    }
                    """;
            IBaseResource resource = fhirContext.newJsonParser().parseResource(json);
            assertThat(extractor.extract(resource)).isEqualTo("1651");
        }
    }

    // ── ServiceRequest (locationReference[]) ─────────────────────────────────

    @Nested
    class ServiceRequestExtraction {

        @Test
        void extractsWithLocationPrefix() {
            ServiceRequest sr = new ServiceRequest();
            sr.addLocationReference(new Reference("Location/1302"));

            assertThat(extractor.extract(sr)).isEqualTo("1302");
        }

        @Test
        void extractsWithOrganizationPrefix() {
            ServiceRequest sr = new ServiceRequest();
            sr.addLocationReference(new Reference("Organization/1302"));

            assertThat(extractor.extract(sr)).isEqualTo("1302");
        }

        @Test
        void extractsFromIdentifierWhenNoReference() {
            ServiceRequest sr = new ServiceRequest();
            Reference locationRef = new Reference();
            locationRef.setIdentifier(new Identifier().setValue("1302"));
            sr.addLocationReference(locationRef);

            assertThat(extractor.extract(sr)).isEqualTo("1302");
        }

        @Test
        void usesFirstEntryWhenMultipleLocations() {
            ServiceRequest sr = new ServiceRequest();
            sr.addLocationReference(new Reference("Location/1302"));
            sr.addLocationReference(new Reference("Location/9999"));

            assertThat(extractor.extract(sr)).isEqualTo("1302");
        }

        @Test
        void returnsNullWhenLocationReferenceAbsent() {
            assertThat(extractor.extract(new ServiceRequest())).isNull();
        }

        @Test
        void extractsFromParsedPayload() {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "503723",
                      "status": "active",
                      "intent": "order",
                      "subject": {"reference": "Patient/123"},
                      "locationReference": [{"reference": "Location/1302", "display": "NCD Upazila"}]
                    }
                    """;
            IBaseResource resource = fhirContext.newJsonParser().parseResource(json);
            assertThat(extractor.extract(resource)).isEqualTo("1302");
        }

        @Test
        void extractsFromParsedPayloadWithOrganizationPrefix() {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "503723",
                      "status": "active",
                      "intent": "order",
                      "subject": {"reference": "Patient/123"},
                      "locationReference": [{"reference": "Organization/1302", "display": "NCD Upazila"}]
                    }
                    """;
            IBaseResource resource = fhirContext.newJsonParser().parseResource(json);
            assertThat(extractor.extract(resource)).isEqualTo("1302");
        }
    }

    // ── Procedure (direct location Reference) ────────────────────────────────

    @Nested
    class ProcedureExtraction {

        @Test
        void extractsWithLocationPrefix() {
            Procedure procedure = new Procedure();
            procedure.setLocation(new Reference("Location/0030"));

            assertThat(extractor.extract(procedure)).isEqualTo("0030");
        }

        @Test
        void extractsWithOrganizationPrefix() {
            Procedure procedure = new Procedure();
            procedure.setLocation(new Reference("Organization/1302"));

            assertThat(extractor.extract(procedure)).isEqualTo("1302");
        }

        @Test
        void returnsNullWhenLocationAbsent() {
            assertThat(extractor.extract(new Procedure())).isNull();
        }
    }

    // ── Immunization (direct location Reference) ──────────────────────────────

    @Nested
    class ImmunizationExtraction {

        @Test
        void extractsWithLocationPrefix() {
            Immunization immunization = new Immunization();
            immunization.setLocation(new Reference("Location/0030"));

            assertThat(extractor.extract(immunization)).isEqualTo("0030");
        }

        @Test
        void extractsWithOrganizationPrefix() {
            Immunization immunization = new Immunization();
            immunization.setLocation(new Reference("Organization/1302"));

            assertThat(extractor.extract(immunization)).isEqualTo("1302");
        }

        @Test
        void returnsNullWhenLocationAbsent() {
            assertThat(extractor.extract(new Immunization())).isNull();
        }
    }

    // ── Resources with no location fields (always pass through) ──────────────

    @Nested
    class NoLocationResources {

        @Test
        void returnsNullForObservation() {
            assertThat(extractor.extract(new Observation())).isNull();
        }

        @Test
        void returnsNullForPatient() {
            assertThat(extractor.extract(new Patient())).isNull();
        }

        @Test
        void returnsNullForRelatedPerson() {
            assertThat(extractor.extract(new RelatedPerson())).isNull();
        }

        @Test
        void returnsNullForCondition() {
            assertThat(extractor.extract(new Condition())).isNull();
        }

        @Test
        void returnsNullForNullInput() {
            assertThat(extractor.extract(null)).isNull();
        }
    }

    // ── source-facility extension (fallback for all resource types) ──────────

    @Nested
    class SourceFacilityExtension {

        private static final String URL = "http://example.org/fhir/StructureDefinition/source-facility";

        @Test
        void extractsFromObservationExtension() {
            Observation observation = new Observation();
            observation.addExtension(new Extension(URL, new StringType("0007")));

            assertThat(extractor.extract(observation)).isEqualTo("0007");
        }

        @Test
        void extractsFromConditionExtension() {
            Condition condition = new Condition();
            condition.addExtension(new Extension(URL, new StringType("0518")));

            assertThat(extractor.extract(condition)).isEqualTo("0518");
        }

        @Test
        void extractsFromMedicationRequestExtension() {
            MedicationRequest medicationRequest = new MedicationRequest();
            medicationRequest.addExtension(new Extension(URL, new StringType("2980")));

            assertThat(extractor.extract(medicationRequest)).isEqualTo("2980");
        }

        @Test
        void locationTakesPrecedenceOverExtension() {
            // A resource carrying both a FHIR location and the extension: location wins.
            ServiceRequest sr = new ServiceRequest();
            sr.addLocationReference(new Reference("Location/1302"));
            sr.addExtension(new Extension(URL, new StringType("9999")));

            assertThat(extractor.extract(sr)).isEqualTo("1302");
        }

        @Test
        void ignoresOtherExtensions() {
            Observation observation = new Observation();
            observation.addExtension(new Extension(
                    "http://example.org/fhir/StructureDefinition/source-system", new StringType("eBuzima")));

            assertThat(extractor.extract(observation)).isNull();
        }

        @Test
        void returnsNullWhenExtensionValueBlank() {
            Observation observation = new Observation();
            observation.addExtension(new Extension(URL, new StringType("")));

            assertThat(extractor.extract(observation)).isNull();
        }

        @Test
        void extractsFromParsedConditionPayload() {
            String json = """
                    {
                      "resourceType": "Condition",
                      "id": "a7ba628f-e994-4eb5-adf6-43fa173b7555",
                      "subject": {"reference": "Patient/221104-0004-9742"},
                      "encounter": {"reference": "Encounter/74722465-f4fc-4f8c-8c38-c8ef8b2fba51"},
                      "extension": [
                        {"url": "http://example.org/fhir/StructureDefinition/source-system", "valueString": "eBuzima"},
                        {"url": "http://example.org/fhir/StructureDefinition/source-facility", "valueString": "0007"}
                      ]
                    }
                    """;
            IBaseResource resource = fhirContext.newJsonParser().parseResource(json);
            assertThat(extractor.extract(resource)).isEqualTo("0007");
        }
    }
}
