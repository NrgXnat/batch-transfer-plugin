package org.nrg.xnatx.plugins.transfer.api;

import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.nrg.xnatx.plugins.transfer.model.ManifestSubmitRequest;
import org.nrg.xnatx.plugins.transfer.model.ManifestSubmitResult;
import org.nrg.xnatx.plugins.transfer.model.ManifestSummary;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationRequest;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationResult;
import org.nrg.xnatx.plugins.transfer.model.TransferCapabilities;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.nrg.xnatx.plugins.transfer.service.ManifestService;
import org.nrg.xnatx.plugins.transfer.service.ManifestValidationException;
import org.nrg.xnatx.plugins.transfer.service.PreflightResolver;
import org.nrg.xnatx.plugins.transfer.service.ScriptCompiler;
import org.nrg.xnatx.plugins.transfer.service.ScriptValidationException;
import org.nrg.xnatx.plugins.transfer.service.TransferCapabilitiesService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.annotations.Api;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Slf4j
@Api("Batch Transfer Api")
@RequestMapping(value = "/transfer")
@XapiRestController
public class BatchTransferApi extends AbstractXapiRestController {

    private final BatchTransferService batchTransferService;
    private final TransferCapabilitiesService capabilitiesService;
    private final ScriptCompiler scriptCompiler;
    private final ManifestService manifestService;
    private final PreflightResolver preflightResolver;

    protected BatchTransferApi(UserManagementServiceI userManagementService, RoleHolder roleHolder,
                               BatchTransferService batchTransferService, TransferCapabilitiesService capabilitiesService,
                               ScriptCompiler scriptCompiler, ManifestService manifestService,
                               PreflightResolver preflightResolver) {
        super(userManagementService, roleHolder);
        this.batchTransferService = batchTransferService;
        this.capabilitiesService = capabilitiesService;
        this.scriptCompiler = scriptCompiler;
        this.manifestService = manifestService;
        this.preflightResolver = preflightResolver;
    }

    @ApiOperation(value = "Submits an async batch transfer request")
    @ApiResponses({ @ApiResponse(code = 200, message = "Request submitted"),
                    @ApiResponse(code = 400, message = "Invalid request (e.g. a custom anon_script on a non-Reimport item)"),
                    @ApiResponse(code = 403, message = "No edit access to a destination project required by a custom anon_script"),
                    @ApiResponse(code = 409, message = "This XNAT build does not support per-import anonymization"),
                    @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = {""}, consumes = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<String> submit(@RequestBody BatchTransfer batchTransferRequest) {
        final List<TransferRequest> requests = batchTransferRequest.getRequests();
        if (requests == null || requests.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        final UserI user = getSessionUser();

        final ResponseEntity<String> guardrail = checkAnonGuardrails(batchTransferRequest.getAnonScript(), requests, user);
        if (guardrail != null) {
            return guardrail;
        }

        batchTransferService.submitTransferRequest(batchTransferRequest, user);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    /**
     * Enforces the custom-anon-script guardrails shared by direct submit and the one-shot manifest submit:
     * feature support (409), Reimport-only (400), edit access on every destination (403), then the
     * {@link ScriptCompiler} size/parse/restriction/binding/charset checks. Returns {@code null} when the
     * batch is clear to submit (including when there is no script), or the error response to return otherwise.
     */
    private ResponseEntity<String> checkAnonGuardrails(final String anonScript, final List<TransferRequest> requests,
                                                       final UserI user) {
        if (StringUtils.isBlank(anonScript)) {
            return null;
        }
        if (!capabilitiesService.isPerImportAnonSupported()) {
            return new ResponseEntity<>(
                    "This XNAT build does not support per-import anonymization (the Anon-Script importer "
                            + "parameter is unavailable). Omit anon_script or upgrade XNAT.",
                    HttpStatus.CONFLICT);
        }
        for (final TransferRequest request : requests) {
            if (request.getMode() != TransferMode.REIMPORT) {
                return new ResponseEntity<>(
                        "A custom anon_script is only valid when every request is a Reimport.",
                        HttpStatus.BAD_REQUEST);
            }
        }
        final Set<String> destinations = new HashSet<>();
        for (final TransferRequest request : requests) {
            destinations.add(request.getDestinationProject());
        }
        for (final String destination : destinations) {
            try {
                if (!Permissions.canEditProject(user, destination)) {
                    return new ResponseEntity<>(
                            "You must have edit access to project " + destination
                                    + " to apply a custom anonymization script.",
                            HttpStatus.FORBIDDEN);
                }
            } catch (Exception e) {
                log.error("Could not verify edit permission on project {}", destination, e);
                return new ResponseEntity<>(
                        "Could not verify edit permission on project " + destination + ".",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        // Size, parse, thread-safety restriction, ${csv.*} binding, and value-charset checks. Throws
        // with the HTTP status the response should carry.
        try {
            scriptCompiler.validateBatch(anonScript, requests);
        } catch (ScriptValidationException e) {
            return new ResponseEntity<>(e.getMessage(), e.getStatus());
        }
        return null;
    }

    @ApiOperation(value = "Reports per-import anonymization capability and limits for this XNAT build")
    @ApiResponses({ @ApiResponse(code = 200, message = "Capabilities returned") })
    @XapiRequestMapping(value = {"capabilities"}, produces = APPLICATION_JSON_VALUE, method = GET)
    @ResponseBody
    public ResponseEntity<TransferCapabilities> capabilities() {
        return new ResponseEntity<>(capabilitiesService.getCapabilities(), HttpStatus.OK);
    }

    // ---- Manifest: parse + light preflight (validate) --------------------------------------------------

    @ApiOperation(value = "Parses a manifest CSV and resolves each row to a source session (no data touched)")
    @ApiResponses({ @ApiResponse(code = 200, message = "Parsed result (including required-column/not-found detail)"),
                    @ApiResponse(code = 400, message = "Unparseable CSV or missing source_project"),
                    @ApiResponse(code = 403, message = "No read access to the source project"),
                    @ApiResponse(code = 413, message = "Manifest exceeds the row cap")})
    @XapiRequestMapping(value = {"validate/manifest"}, consumes = APPLICATION_JSON_VALUE,
            produces = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<?> validateManifest(@RequestBody final ManifestValidationRequest request) {
        final TransferMode mode = request.getMode() == null ? TransferMode.REIMPORT : request.getMode();
        return doValidateManifest(request.getSourceProject(), mode, request.getManifestCsv(),
                request.getAnonScript(), getSessionUser());
    }

    @ApiOperation(value = "Parses an uploaded manifest CSV and resolves each row to a source session")
    @XapiRequestMapping(value = {"validate/manifest"}, consumes = MULTIPART_FORM_DATA_VALUE,
            produces = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<?> validateManifestUpload(
            @RequestParam("manifest") final MultipartFile manifest,
            @RequestParam("source_project") final String sourceProject,
            @RequestParam(value = "mode", required = false) final String mode,
            @RequestParam(value = "anon_script", required = false) final String anonScript) {
        final TransferMode resolvedMode = resolveMode(mode);
        if (resolvedMode == null) {
            return new ResponseEntity<>("Invalid mode: " + mode, HttpStatus.BAD_REQUEST);
        }
        final String csv;
        try {
            csv = readCsv(manifest);
        } catch (IOException e) {
            return new ResponseEntity<>("Could not read the uploaded manifest: " + e.getMessage(),
                    HttpStatus.BAD_REQUEST);
        }
        return doValidateManifest(sourceProject, resolvedMode, csv, anonScript, getSessionUser());
    }

    private ResponseEntity<?> doValidateManifest(final String sourceProject, final TransferMode mode,
                                                 final String csv, final String anonScript, final UserI user) {
        if (StringUtils.isBlank(sourceProject)) {
            return new ResponseEntity<>("source_project is required.", HttpStatus.BAD_REQUEST);
        }
        if (!canReadProject(sourceProject, user)) {
            return new ResponseEntity<>("You do not have read access to project " + sourceProject + ".",
                    HttpStatus.FORBIDDEN);
        }

        final ManifestValidationResult result;
        try {
            result = manifestService.parse(csv, anonScript);
        } catch (ManifestValidationException e) {
            return new ResponseEntity<>(e.getMessage(), e.getStatus());
        }

        if (result.isRequiredPresent() && !result.getRows().isEmpty()) {
            preflightResolver.resolveAll(sourceProject, result.getRows(), user);
            int matched = 0;
            for (final ManifestRow row : result.getRows()) {
                if (ManifestRow.STATUS_MATCHED.equals(row.getStatus())) {
                    matched++;
                }
            }
            result.setSummary(new ManifestSummary(matched, result.getRows().size() - matched));
        }
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    // ---- Manifest: one-shot resolve-and-submit --------------------------------------------------------

    @ApiOperation(value = "Resolves a manifest and submits the matched rows as a batch transfer")
    @ApiResponses({ @ApiResponse(code = 200, message = "Submitted (with skipped-not-found count)"),
                    @ApiResponse(code = 400, message = "Invalid request / no matched rows / anon-script check failed"),
                    @ApiResponse(code = 403, message = "No read access to source, or no edit access for a custom anon_script"),
                    @ApiResponse(code = 409, message = "This XNAT build does not support per-import anonymization"),
                    @ApiResponse(code = 413, message = "Manifest exceeds the row cap")})
    @XapiRequestMapping(value = {"manifest"}, consumes = APPLICATION_JSON_VALUE,
            produces = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<?> submitManifest(@RequestBody final ManifestSubmitRequest request) {
        final TransferMode mode = request.getMode() == null ? TransferMode.REIMPORT : request.getMode();
        return doSubmitManifest(request.getSourceProject(), request.getDestinationProject(), mode,
                request.getManifestCsv(), request.getAnonScript(), request.isAnonReplacePipeline(),
                request.getTrackingId(), getSessionUser());
    }

    @ApiOperation(value = "Resolves an uploaded manifest and submits the matched rows as a batch transfer")
    @XapiRequestMapping(value = {"manifest"}, consumes = MULTIPART_FORM_DATA_VALUE,
            produces = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<?> submitManifestUpload(
            @RequestParam("manifest") final MultipartFile manifest,
            @RequestParam("source_project") final String sourceProject,
            @RequestParam("destination_project") final String destinationProject,
            @RequestParam(value = "mode", required = false) final String mode,
            @RequestParam(value = "anon_script", required = false) final String anonScript,
            @RequestParam(value = "anon_replace_pipeline", required = false, defaultValue = "false")
                    final boolean anonReplacePipeline,
            @RequestParam(value = "tracking_id", required = false) final String trackingId) {
        final TransferMode resolvedMode = resolveMode(mode);
        if (resolvedMode == null) {
            return new ResponseEntity<>("Invalid mode: " + mode, HttpStatus.BAD_REQUEST);
        }
        final String csv;
        try {
            csv = readCsv(manifest);
        } catch (IOException e) {
            return new ResponseEntity<>("Could not read the uploaded manifest: " + e.getMessage(),
                    HttpStatus.BAD_REQUEST);
        }
        return doSubmitManifest(sourceProject, destinationProject, resolvedMode, csv, anonScript,
                anonReplacePipeline, trackingId, getSessionUser());
    }

    private ResponseEntity<?> doSubmitManifest(final String sourceProject, final String destinationProject,
                                               final TransferMode mode, final String csv, final String anonScript,
                                               final boolean anonReplacePipeline, final String trackingId,
                                               final UserI user) {
        if (StringUtils.isBlank(sourceProject)) {
            return new ResponseEntity<>("source_project is required.", HttpStatus.BAD_REQUEST);
        }
        if (StringUtils.isBlank(destinationProject)) {
            return new ResponseEntity<>("destination_project is required.", HttpStatus.BAD_REQUEST);
        }
        if (!canReadProject(sourceProject, user)) {
            return new ResponseEntity<>("You do not have read access to project " + sourceProject + ".",
                    HttpStatus.FORBIDDEN);
        }

        final ManifestValidationResult result;
        try {
            result = manifestService.parse(csv, anonScript);
        } catch (ManifestValidationException e) {
            return new ResponseEntity<>(e.getMessage(), e.getStatus());
        }
        if (!result.isRequiredPresent()) {
            return new ResponseEntity<>("The manifest is missing required column(s): "
                    + String.join(", ", result.getMissingColumns()) + ".", HttpStatus.BAD_REQUEST);
        }

        preflightResolver.resolveAll(sourceProject, result.getRows(), user);

        final List<TransferRequest> requests = new ArrayList<>();
        int skipped = 0;
        for (final ManifestRow row : result.getRows()) {
            if (ManifestRow.STATUS_MATCHED.equals(row.getStatus())) {
                final TransferRequest request = new TransferRequest(destinationProject, row.getResolvedId(), mode);
                request.setCsvValues(row.getCsvValues());
                requests.add(request);
            } else {
                skipped++;
            }
        }
        if (requests.isEmpty()) {
            return new ResponseEntity<>("No matched sessions to submit (" + skipped + " row(s) not found).",
                    HttpStatus.BAD_REQUEST);
        }

        final BatchTransfer batch = new BatchTransfer(requests, trackingId);
        batch.setAnonScript(anonScript);
        batch.setAnonReplacePipeline(anonReplacePipeline);

        final ResponseEntity<String> guardrail = checkAnonGuardrails(anonScript, requests, user);
        if (guardrail != null) {
            return guardrail;
        }

        batchTransferService.submitTransferRequest(batch, user);
        return new ResponseEntity<>(new ManifestSubmitResult(batch.getTrackingId(), requests.size(), skipped),
                HttpStatus.OK);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /**
     * True when the user can read (or the project is otherwise resolvable to) {@code project}. Protected seam
     * (like the service-layer {@code runImporter}/{@code destinationAutoArchives}) so tests can stub the read
     * check without mocking the {@code XnatProjectdata} static.
     */
    protected boolean canReadProject(final String project, final UserI user) {
        try {
            return XnatProjectdata.getProjectByIDorAlias(project, user, false) != null;
        } catch (Exception e) {
            log.error("Could not verify read access on project {}", project, e);
            return false;
        }
    }

    /** Blank → the default Reimport; a known value → its mode; anything else → null (an invalid mode). */
    private static TransferMode resolveMode(final String mode) {
        if (StringUtils.isBlank(mode)) {
            return TransferMode.REIMPORT;
        }
        for (final TransferMode candidate : TransferMode.values()) {
            if (candidate.getValue().equalsIgnoreCase(mode)) {
                return candidate;
            }
        }
        return null;
    }

    /** Read an uploaded manifest as UTF-8 text, stripping a leading byte-order mark. */
    private static String readCsv(final MultipartFile file) throws IOException {
        try (InputStream in = new BOMInputStream(file.getInputStream())) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }
}
