package org.nrg.xnatx.plugins.transfer.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.nrg.xnatx.plugins.transfer.model.ManifestSubmitRequest;
import org.nrg.xnatx.plugins.transfer.model.ManifestSubmitResult;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationRequest;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationResult;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.nrg.xnatx.plugins.transfer.service.ManifestService;
import org.nrg.xnatx.plugins.transfer.service.ManifestValidationException;
import org.nrg.xnatx.plugins.transfer.service.PreflightResolver;
import org.nrg.xnatx.plugins.transfer.service.ScriptCompiler;
import org.nrg.xnatx.plugins.transfer.service.TransferCapabilitiesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the manifest endpoints on {@link BatchTransferApi} — the HTTP wiring the service-layer tests
 * ({@code ManifestServiceTest}/{@code PreflightResolverTest}) don't reach: source-project read gate, parse
 * error → status mapping, mode parsing, and the one-shot request-building + skip-not-found. The
 * {@code ManifestService}/{@code PreflightResolver}/{@code BatchTransferService} collaborators are mocked, so
 * these assert routing/orchestration, not parsing or resolution.
 *
 * <p>{@code getSessionUser()} reads the Spring {@code SecurityContextHolder}; a mock {@link UserI} is set as
 * the authentication principal ({@code Users.getUserPrincipal} returns a {@code UserI} principal verbatim), so
 * no security wiring is stubbed. The controller is a spy so the {@code canReadProject} seam can be stubbed for
 * the read gate (its underlying {@code XnatProjectdata} static is declared on a non-public parent and can't be
 * mocked directly).
 */
public class BatchTransferApiTest {

    private static final String SRC  = "SRC_PROJECT";
    private static final String DEST = "DEST_PROJECT";

    private BatchTransferService         service;
    private TransferCapabilitiesService  capabilities;
    private ScriptCompiler               scriptCompiler;
    private ManifestService              manifestService;
    private PreflightResolver            preflightResolver;
    private UserI                        user;
    private BatchTransferApi             api;

    @Before
    public void setUp() {
        service           = mock(BatchTransferService.class);
        capabilities      = mock(TransferCapabilitiesService.class);
        scriptCompiler    = mock(ScriptCompiler.class);
        manifestService   = mock(ManifestService.class);
        preflightResolver = mock(PreflightResolver.class);
        user              = mock(UserI.class);

        api = spy(new BatchTransferApi(mock(UserManagementServiceI.class), mock(RoleHolder.class),
                service, capabilities, scriptCompiler, manifestService, preflightResolver));

        final Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Default: the source project is readable. Individual tests override to false for the 403 case.
        doReturn(true).when(api).canReadProject(anyString(), any(UserI.class));
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- validate/manifest ---------------------------------------------------------------------------

    @Test
    public void validateManifest_missingSourceProject_returns400() {
        final ResponseEntity<?> resp = api.validateManifest(new ManifestValidationRequest());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void validateManifest_noReadAccess_returns403() {
        doReturn(false).when(api).canReadProject(anyString(), any(UserI.class));
        final ResponseEntity<?> resp = api.validateManifest(validationRequest(SRC, null));
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    public void validateManifest_parseOverCap_returns413() throws Exception {
        when(manifestService.parse(any(), any()))
                .thenThrow(new ManifestValidationException(HttpStatus.PAYLOAD_TOO_LARGE, "too big"));
        final ResponseEntity<?> resp = api.validateManifest(validationRequest(SRC, null));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode());
    }

    @Test
    public void validateManifest_happyPath_resolvesAndComputesSummary() throws Exception {
        final ManifestValidationResult result = result(true,
                matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1")),
                notFoundRow("S9", "SESS9", ManifestRow.STATUS_SUBJECT_NOT_FOUND));
        when(manifestService.parse(any(), any())).thenReturn(result);

        final ResponseEntity<?> resp = api.validateManifest(validationRequest(SRC, null));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user));
        final ManifestValidationResult body = (ManifestValidationResult) resp.getBody();
        assertEquals(1, body.getSummary().getMatched());
        assertEquals(1, body.getSummary().getNotFound());
    }

    @Test
    public void validateManifestUpload_invalidMode_returns400() throws Exception {
        final ResponseEntity<?> resp = api.validateManifestUpload(
                mock(MultipartFile.class), SRC, "NotAMode", null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(manifestService, never()).parse(any(), any());   // rejected before reading the file
    }

    @Test
    public void validateManifestUpload_happyPath_readsFileAndReturns200() throws Exception {
        final MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(
                "source_subject_label,source_session_label\nS1,SESS1\n".getBytes(StandardCharsets.UTF_8)));
        when(manifestService.parse(any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", Collections.<String, String>emptyMap())));

        final ResponseEntity<?> resp = api.validateManifestUpload(file, SRC, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ---- manifest (one-shot submit) ------------------------------------------------------------------

    @Test
    public void submitManifest_missingDestinationProject_returns400() {
        final ManifestSubmitRequest req = new ManifestSubmitRequest();
        req.setSourceProject(SRC);            // no destination
        final ResponseEntity<?> resp = api.submitManifest(req);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    public void submitManifest_missingRequiredColumns_returns400() throws Exception {
        final ManifestValidationResult result = result(false);
        result.setMissingColumns(Arrays.asList("source_session_label"));
        when(manifestService.parse(any(), any())).thenReturn(result);

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submitManifest_noMatchedRows_returns400() throws Exception {
        when(manifestService.parse(any(), any())).thenReturn(result(true,
                notFoundRow("S9", "SESS9", ManifestRow.STATUS_SESSION_NOT_FOUND)));

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submitManifest_happyPath_submitsMatchedRowsAndSkipsNotFound() throws Exception {
        when(manifestService.parse(any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1")),
                matchedRow("S2", "SESS2", "E2", csv("pid", "ANON-2")),
                notFoundRow("S9", "SESS9", ManifestRow.STATUS_SUBJECT_NOT_FOUND)));

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk_1"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());

        final ArgumentCaptor<BatchTransfer> batch = ArgumentCaptor.forClass(BatchTransfer.class);
        verify(service).submitTransferRequest(batch.capture(), eq(user));
        final List<TransferRequest> requests = batch.getValue().getRequests();
        assertEquals(2, requests.size());
        assertEquals("E1", requests.get(0).getId());
        assertEquals(DEST, requests.get(0).getDestinationProject());
        assertEquals(TransferMode.REIMPORT, requests.get(0).getMode());
        assertEquals("ANON-1", requests.get(0).getCsvValues().get("pid"));

        final ManifestSubmitResult body = (ManifestSubmitResult) resp.getBody();
        assertEquals("trk_1", body.getTrackingId());
        assertEquals(2, body.getItemCount());
        assertEquals(1, body.getSkippedNotFound());
    }

    @Test
    public void submitManifest_anonScriptOnUnsupportedBuild_returns409() throws Exception {
        when(manifestService.parse(any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1"))));
        when(capabilities.isPerImportAnonSupported()).thenReturn(false);

        final ResponseEntity<?> resp = api.submitManifest(
                submitRequest(SRC, DEST, "version \"6.1\"\n(0010,0010) := \"X\"", "trk"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private static ManifestValidationRequest validationRequest(final String sourceProject, final String anonScript) {
        final ManifestValidationRequest req = new ManifestValidationRequest();
        req.setSourceProject(sourceProject);
        req.setManifestCsv("source_subject_label,source_session_label\nS1,SESS1\n");
        req.setAnonScript(anonScript);
        return req;
    }

    private static ManifestSubmitRequest submitRequest(final String source, final String dest,
                                                       final String anonScript, final String trackingId) {
        final ManifestSubmitRequest req = new ManifestSubmitRequest();
        req.setSourceProject(source);
        req.setDestinationProject(dest);
        req.setManifestCsv("source_subject_label,source_session_label\nS1,SESS1\n");
        req.setAnonScript(anonScript);
        req.setTrackingId(trackingId);
        return req;
    }

    private static ManifestValidationResult result(final boolean requiredPresent, final ManifestRow... rows) {
        final ManifestValidationResult result = new ManifestValidationResult();
        result.setRequiredPresent(requiredPresent);
        result.setRows(new ArrayList<>(Arrays.asList(rows)));
        return result;
    }

    private static ManifestRow matchedRow(final String subj, final String sess, final String resolvedId,
                                          final Map<String, String> csvValues) {
        final ManifestRow row = new ManifestRow();
        row.setSourceSubjectLabel(subj);
        row.setSourceSessionLabel(sess);
        row.setStatus(ManifestRow.STATUS_MATCHED);
        row.setResolvedId(resolvedId);
        row.setCsvValues(csvValues);
        return row;
    }

    private static ManifestRow notFoundRow(final String subj, final String sess, final String status) {
        final ManifestRow row = new ManifestRow();
        row.setSourceSubjectLabel(subj);
        row.setSourceSessionLabel(sess);
        row.setStatus(status);
        return row;
    }

    private static Map<String, String> csv(final String key, final String value) {
        final Map<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}
