package org.openphc.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Extracts the facility identifier from FHIR R4 resources.
 *
 * <p>Extraction strategies (tried in order):
 * <ol>
 *   <li>For {@code Encounter}: {@code hospitalization.origin} first, then
 *       {@code location[0].location} as a fallback — see {@link #extractFromEncounter} for why,
 *       and note the {@code source-facility} extension is deliberately never consulted here.</li>
 *   <li>For every other resource type: {@code getLocationReference()} returning
 *       {@code List<Reference>} (e.g. {@code ServiceRequest}), then {@code getLocation()}
 *       returning a direct {@code Reference} (e.g. {@code Procedure}, {@code Immunization}),
 *       then the {@code source-facility} extension as a fallback (e.g. {@code Observation},
 *       {@code Condition}, {@code MedicationRequest}, which have no FHIR location at all).</li>
 * </ol>
 *
 * <p>Any {@code ResourceType/id} prefix (e.g. {@code Location/0030}, {@code Organization/1302})
 * is stripped generically — the bare ID after the last {@code /} is used for filter comparison.
 *
 * <p>Returns {@code null} only when the resource carries neither location information nor (for
 * non-Encounter types) a {@code source-facility} extension — such events pass through the
 * facility filter unconditionally.
 */
@Component
public class FacilityIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(FacilityIdExtractor.class);

    /**
     * URL suffix of the source system's facility extension. The full URL is
     * {@code http://example.org/fhir/StructureDefinition/source-facility}; matching on the
     * suffix keeps extraction working if the source-system base URL ever changes.
     */
    private static final String SOURCE_FACILITY_EXTENSION_SUFFIX = "source-facility";

    /**
     * Extracts the facility ID from the given FHIR R4 resource.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code Encounter} — {@code hospitalization.origin} first, {@code
     *       location[0].location} as a fallback; the {@code source-facility} extension is never
     *       consulted. See {@link #extractFromEncounter} for why.</li>
     *   <li>Any other resource with {@code locationReference[]} (e.g. {@code ServiceRequest}) —
     *       first entry's reference is used</li>
     *   <li>Any other resource with a direct {@code location} {@link Reference}
     *       (e.g. {@code Procedure}, {@code Immunization})</li>
     *   <li>Fallback for all non-Encounter resource types: the {@code source-facility} extension
     *       (e.g. {@code Observation}, {@code Condition}, {@code MedicationRequest})</li>
     * </ol>
     *
     * <p>Returns {@code null} only when the resource carries neither location information nor
     * (for non-Encounter types) a {@code source-facility} extension (e.g. {@code Patient},
     * {@code RelatedPerson}), causing the event to pass through the facility filter unconditionally.
     *
     * @param resource the parsed FHIR R4 resource; may be any type
     * @return bare facility ID (e.g. {@code "1302"}), or {@code null} if no location present
     */
    public String extract(IBaseResource resource) {
        if (resource == null) {
            return null;
        }

        if (resource instanceof Encounter encounter) {
            return extractFromEncounter(encounter);
        }

        // Location-based extraction for non-Encounter types.
        // Try locationReference[] (e.g. ServiceRequest) then location (e.g. Procedure, Immunization).
        // Both List<Reference> and direct Reference shapes are handled by the same reflective helper.
        Reference locationRef = resolveLocationReference(resource, "getLocationReference");
        if (locationRef == null) {
            locationRef = resolveLocationReference(resource, "getLocation");
        }
        String facilityId = locationRef != null ? extractId(locationRef, resource.fhirType()) : null;

        // Fallback: the source system's 'source-facility' extension, carried by every
        // resource type (Observation, Condition, MedicationRequest, ...) — including those
        // that have no FHIR location, which is why they previously resolved no facility.
        if (facilityId == null) {
            facilityId = extractFromSourceFacilityExtension(resource);
        }

        return facilityId;
    }

    /**
     * Extracts the facility ID for an {@code Encounter}: {@code hospitalization.origin} first,
     * falling back to {@code location[0].location} only when {@code hospitalization} is absent.
     * The {@code source-facility} extension is deliberately never consulted for {@code Encounter}.
     *
     * <p>Per FHIR R4 (<a href="https://hl7.org/fhir/R4/encounter.html">hl7.org/fhir/R4/encounter.html</a>),
     * {@code hospitalization} is only ever populated on a {@code TRANSFER_ENCOUNTER} — a plain visit
     * or consultation encounter never carries it. Unlike other resource types,
     * {@code Encounter.location[]} does not reliably identify the <em>reporting</em> facility — its
     * role depends on the encounter type. For a plain visit or consultation it matches the reporting
     * facility, but for a {@code TRANSFER_ENCOUNTER} it holds the transfer <em>destination</em> (e.g.
     * {@code "location": [{"location": {"reference": "Location/<destination-uuid>"}}]}), while
     * {@code hospitalization.origin} carries the true source facility. Trusting {@code location[0]}
     * for a transfer therefore compares a destination-facility UUID against
     * {@code FACILITY_FILTER_IDS} — a list of short numeric codes — which can never match, so every
     * transfer-out event was silently dropped by the facility filter regardless of whether the true
     * reporting facility was allow-listed.
     *
     * <p>{@code hospitalization.origin} is present exactly on the encounter type where
     * {@code location[0]} is ambiguous (transfers), and absent exactly where {@code location[0]} is
     * reliable (plain visit/consultation) — so checking it first fixes the transfer case without
     * changing behavior for any other encounter type.
     *
     * <p>Example payload fragment:
     * <pre>{@code
     * "location": [{ "location": { "reference": "Location/0030" } }]
     * }</pre>
     * → returns {@code "0030"} (no {@code hospitalization} present)
     */
    private String extractFromEncounter(Encounter encounter) {
        if (encounter.hasHospitalization() && encounter.getHospitalization().hasOrigin()) {
            String facilityId = extractId(encounter.getHospitalization().getOrigin(), "Encounter");
            if (facilityId != null) {
                return facilityId;
            }
        }

        List<Encounter.EncounterLocationComponent> locations = encounter.getLocation();
        if (locations == null || locations.isEmpty()) {
            return null;
        }

        Reference locationRef = locations.get(0).getLocation();
        if (locationRef == null) {
            return null;
        }

        return extractId(locationRef, "Encounter");
    }

    /**
     * Extracts the facility ID from the source system's {@code source-facility} extension,
     * which every resource type carries regardless of whether it has a FHIR location:
     * <pre>{@code
     * "extension": [{ "url": ".../source-facility", "valueString": "0007" }]
     * }</pre>
     * → returns {@code "0007"}.
     *
     * <p>Never consulted for {@code Encounter} (see {@link #extractFromEncounter}); for every
     * other resource type it is the fallback that gives a facility to resources with no FHIR
     * {@code location} at all (e.g. {@code Observation}, {@code Condition},
     * {@code MedicationRequest}).
     *
     * @param resource the parsed FHIR R4 resource
     * @return the facility ID from the extension, or {@code null} if it is absent or empty
     */
    private String extractFromSourceFacilityExtension(IBaseResource resource) {
        if (!(resource instanceof DomainResource domainResource)) {
            return null;
        }

        for (Extension extension : domainResource.getExtension()) {
            String url = extension.getUrl();
            if (url != null && url.endsWith(SOURCE_FACILITY_EXTENSION_SUFFIX) && extension.hasValue()) {
                String facilityId = extension.getValue().primitiveValue();
                if (facilityId != null && !facilityId.isBlank()) {
                    log.debug("Extracted facility ID '{}' from {} source-facility extension", facilityId, resource.fhirType());
                    return facilityId;
                }
            }
        }

        return null;
    }

    /**
     * Extracts the bare facility ID from a {@link Reference}.
     *
     * <p>Strips any {@code ResourceType/} prefix generically — only the segment after
     * the last {@code /} is used, so both {@code "Location/1302"} and
     * {@code "Organization/1302"} produce {@code "1302"}.
     *
     * <p>Falls back to {@code identifier.value} when no reference string is set
     * (e.g. {@code "locationReference": [{ "identifier": { "value": "1302" } }]}).
     *
     * @param locationRef  the FHIR Reference to extract from
     * @param resourceType the FHIR resource type — used only for debug logging
     * @return bare facility ID, or {@code null} if the reference carries no usable value
     */
    private String extractId(Reference locationRef, String resourceType) {
        if (locationRef.hasReference()) {
            String rawReference = locationRef.getReference(); // e.g. "Location/1302" or "Organization/1302"
            String facilityId = rawReference.contains("/") ? rawReference.substring(rawReference.lastIndexOf('/') + 1) : rawReference;
            if (!facilityId.isBlank()) {
                log.debug("Extracted facility ID '{}' from {} location reference", facilityId, resourceType);
                return facilityId;
            }
        }

        // Fallback: identifier.value when reference string is absent
        if (locationRef.hasIdentifier() && locationRef.getIdentifier().hasValue()) {
            String facilityId = locationRef.getIdentifier().getValue();
            log.debug("Extracted facility ID '{}' from {} location identifier", facilityId, resourceType);
            return facilityId;
        }

        return null;
    }

    /**
     * Reflectively invokes {@code methodName()} on {@code resource} and resolves the result
     * to a {@link Reference}, handling both field shapes used across FHIR R4 resources:
     *
     * <ul>
     *   <li>{@code List<Reference>} — iterates entries and returns the first one that has a
     *       usable reference string or identifier (e.g. {@code getLocationReference()} on
     *       {@code ServiceRequest}):
     *       <pre>{@code "locationReference": [{ "reference": "Location/1302" }] }</pre></li>
     *   <li>Direct {@code Reference} — returned as-is if it has a reference or identifier
     *       (e.g. {@code getLocation()} on {@code Procedure} or {@code Immunization}):
     *       <pre>{@code "location": { "reference": "Location/0030" } }</pre></li>
     * </ul>
     *
     * @param resource    the FHIR resource to introspect
     * @param methodName  no-arg getter to invoke (e.g. {@code "getLocationReference"}, {@code "getLocation"})
     * @return the first usable {@link Reference}, or {@code null} if none found
     */
    private Reference resolveLocationReference(IBaseResource resource, String methodName) {
        try {
            // Reflectively invoke the getter (e.g. getLocationReference, getLocation)
            Method getter = resource.getClass().getMethod(methodName);
            Object result = getter.invoke(resource);

            // List<Reference> shape — e.g. ServiceRequest.locationReference[]
            // Iterate all entries and return the first one that carries a usable value.
            // Source systems may send Organization/ or Location/ prefixes — accept any.
            if (result instanceof List<?> locationList) {
                return locationList.stream()
                        .filter(item -> item instanceof Reference)       // skip non-Reference entries
                        .map(item -> (Reference) item)
                        .filter(locationRef -> locationRef.hasReference() || locationRef.hasIdentifier()) // must have a value
                        .findFirst()
                        .orElse(null);
            }

            // Direct Reference shape — e.g. Procedure.location, Immunization.location
            // Return only if the reference actually carries a value; null otherwise.
            if (result instanceof Reference locationRef) {
                return (locationRef.hasReference() || locationRef.hasIdentifier()) ? locationRef : null;
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            // Method does not exist on this resource type — not applicable, fall through
        }
        return null;
    }
}
