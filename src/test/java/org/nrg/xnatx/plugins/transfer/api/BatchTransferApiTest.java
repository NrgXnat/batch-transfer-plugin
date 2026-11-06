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
import org.nrg.xnatx.plugins.transfer.model.ScriptValidationRequest;
import org.nrg.xnatx.plugins.transfer.model.ScriptValidationResult;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        // The resolver is mocked: fixture rows already carry their statuses, so it hands them back unchanged.
        when(preflightResolver.resolveAll(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
        when(manifestService.parse(any(), any(), any()))
                .thenThrow(new ManifestValidationException(HttpStatus.PAYLOAD_TOO_LARGE, "too big"));
        final ResponseEntity<?> resp = api.validateManifest(validationRequest(SRC, null));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode());
    }

    @Test
    public void validateManifest_happyPath_resolvesAndComputesSummary() throws Exception {
        final ManifestValidationResult result = result(true,
                matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1")),
                notFoundRow("S9", "SESS9", ManifestRow.STATUS_SUBJECT_NOT_FOUND));
        when(manifestService.parse(any(), any(), any())).thenReturn(result);

        final ResponseEntity<?> resp = api.validateManifest(validationRequest(SRC, null));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user), eq(TransferMode.REIMPORT), eq(false));
        final ManifestValidationResult body = (ManifestValidationResult) resp.getBody();
        assertEquals(1, body.getSummary().getMatched());
        assertEquals(1, body.getSummary().getNotFound());
    }

    @Test
    public void validateManifest_fileCountFlag_threadedToResolver() throws Exception {
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", Collections.<String, String>emptyMap())));

        final ManifestValidationRequest req = validationRequest(SRC, null);
        req.setFileCount(true);
        final ResponseEntity<?> resp = api.validateManifest(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user), eq(TransferMode.REIMPORT), eq(true));
    }

    @Test
    public void validateManifestUpload_invalidMode_returns400() throws Exception {
        final ResponseEntity<?> resp = api.validateManifestUpload(
                mock(MultipartFile.class), SRC, "NotAMode", null, null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(manifestService, never()).parse(any(), any(), any());   // rejected before reading the file
    }

    @Test
    public void validateManifestUpload_happyPath_readsFileAndReturns200() throws Exception {
        final MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(
                "source_subject_label,source_session_label\nS1,SESS1\n".getBytes(StandardCharsets.UTF_8)));
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", Collections.<String, String>emptyMap())));

        final ResponseEntity<?> resp = api.validateManifestUpload(file, SRC, null, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    public void validateManifest_summaryCountsEntriesAndRowsByStatus() throws Exception {
        final ManifestRow sessionA = matchedRow("S1", "SESS_A", "E1", csv("pid", "ANON-1"));
        final ManifestRow sessionB = matchedRow("S1", "SESS_B", "E2", csv("pid", "ANON-1"));
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true, sessionA, sessionB,
                notFoundRow("S2", "", ManifestRow.STATUS_NO_SESSIONS),
                notFoundRow("S3", "", ManifestRow.STATUS_DUPLICATE_SESSION),
                notFoundRow("S3", "SESS3", ManifestRow.STATUS_DUPLICATE_SESSION),
                notFoundRow("S9", "SESS9", ManifestRow.STATUS_SESSION_NOT_FOUND)));

        final ManifestValidationResult body =
                (ManifestValidationResult) api.validateManifest(validationRequest(SRC, null)).getBody();

        assertEquals("matched counts sessions", 2, body.getSummary().getMatched());
        assertEquals(1, body.getSummary().getNotFound());
        assertEquals(1, body.getSummary().getNoSessions());
        assertEquals(2, body.getSummary().getDuplicateSession());
    }

    @Test
    public void validateManifest_modeThreadedToResolver() throws Exception {
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                matchedRow("S1", "", "SUBJ1", Collections.<String, String>emptyMap())));
        final ManifestValidationRequest req = validationRequest(SRC, null);
        req.setMode(TransferMode.CLONE);

        api.validateManifest(req);

        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user), eq(TransferMode.CLONE), eq(false));
    }

    // ---- validate/script -----------------------------------------------------------------------------

    @Test
    public void validateScript_blankScript_returns400() {
        assertEquals(HttpStatus.BAD_REQUEST,
                api.validateScript(new ScriptValidationRequest()).getStatusCode());
        final ScriptValidationRequest blank = new ScriptValidationRequest();
        blank.setAnonScript("   ");
        assertEquals(HttpStatus.BAD_REQUEST, api.validateScript(blank).getStatusCode());
        verify(scriptCompiler, never()).validate(anyString(), any());
    }

    @Test
    public void validateScript_returnsCompilerVerdictWith200() {
        final ScriptValidationResult verdict = new ScriptValidationResult();
        verdict.setValid(true);
        when(scriptCompiler.validate(anyString(), any())).thenReturn(verdict);

        final ScriptValidationRequest req = new ScriptValidationRequest();
        req.setAnonScript("version \"6.1\"\n(0010,0010) := \"${csv.pid}\"");
        req.setManifestColumns(Arrays.asList("pid"));

        final ResponseEntity<?> resp = api.validateScript(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(verdict, resp.getBody());
        verify(scriptCompiler).validate(req.getAnonScript(), req.getManifestColumns());
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
        when(manifestService.parse(any(), any(), any())).thenReturn(result);

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submitManifest_noMatchedRows_returns400() throws Exception {
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                notFoundRow("S9", "SESS9", ManifestRow.STATUS_SESSION_NOT_FOUND)));

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submitManifest_happyPath_submitsMatchedRowsAndSkipsNotFound() throws Exception {
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
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
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1"))));
        when(capabilities.isPerImportAnonSupported()).thenReturn(false);

        final ResponseEntity<?> resp = api.submitManifest(
                submitRequest(SRC, DEST, "version \"6.1\"\n(0010,0010) := \"X\"", "trk"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submitManifest_routingLabels_threadedToRequests() throws Exception {
        final ManifestRow row = matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1"));
        row.setDestinationSubjectLabel("ANON-042");
        row.setDestinationSessionLabel("STUDY2_042");
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true, row));

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        final ArgumentCaptor<BatchTransfer> batch = ArgumentCaptor.forClass(BatchTransfer.class);
        verify(service).submitTransferRequest(batch.capture(), eq(user));
        final TransferRequest req = batch.getValue().getRequests().get(0);
        assertEquals("ANON-042", req.getDestinationSubjectLabel());
        assertEquals("STUDY2_042", req.getDestinationSessionLabel());
    }

    @Test
    public void submitManifest_matchedRowWithValueError_returns400() throws Exception {
        final ManifestRow row = matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1"));
        row.getValueErrors().add("destination_subject_label is not a valid XNAT label");
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true, row));

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submitManifest_duplicateSession_returns400() throws Exception {
        final ManifestRow subject = notFoundRow("S1", "", ManifestRow.STATUS_DUPLICATE_SESSION);
        subject.setIndex(1);
        final ManifestRow session = notFoundRow("S1", "SESS1", ManifestRow.STATUS_DUPLICATE_SESSION);
        session.setIndex(2);
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true, subject, session,
                matchedRow("S2", "SESS2", "E2", csv("pid", "ANON-2"))));

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(((String) resp.getBody()).contains("rows 1, 2"));
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submitManifest_preserveSettings_threadedToReimportRequests() throws Exception {
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1"))));
        final ManifestSubmitRequest req = submitRequest(SRC, DEST, null, "trk");
        req.setPreserveSubjectLabel(true);
        req.setPreserveSessionLabel(false);

        api.submitManifest(req);

        final ArgumentCaptor<BatchTransfer> batch = ArgumentCaptor.forClass(BatchTransfer.class);
        verify(service).submitTransferRequest(batch.capture(), eq(user));
        final TransferRequest request = batch.getValue().getRequests().get(0);
        assertEquals(Boolean.TRUE, request.getPreserveSubjectLabel());
        assertEquals(Boolean.FALSE, request.getPreserveSessionLabel());
    }

    @Test
    public void submitManifest_preserveSettings_notAppliedToShare() throws Exception {
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                matchedRow("S1", "", "SUBJ1", Collections.<String, String>emptyMap())));
        final ManifestSubmitRequest req = submitRequest(SRC, DEST, null, "trk");
        req.setMode(TransferMode.SHARE);
        req.setPreserveSubjectLabel(true);

        api.submitManifest(req);

        final ArgumentCaptor<BatchTransfer> batch = ArgumentCaptor.forClass(BatchTransfer.class);
        verify(service).submitTransferRequest(batch.capture(), eq(user));
        final TransferRequest request = batch.getValue().getRequests().get(0);
        assertEquals("SUBJ1", request.getId());
        assertEquals(TransferMode.SHARE, request.getMode());
        assertNull(request.getPreserveSubjectLabel());
    }

    @Test
    public void submitManifest_expandedSubjectRow_submitsEachSessionWithTheRowsValues() throws Exception {
        final ManifestRow sessionA = matchedRow("S1", "SESS_A", "E1", csv("pid", "ANON-1"));
        sessionA.setDestinationSubjectLabel("ANON_S1");
        final ManifestRow sessionB = matchedRow("S1", "SESS_B", "E2", csv("pid", "ANON-1"));
        sessionB.setDestinationSubjectLabel("ANON_S1");
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true, sessionA, sessionB));

        final ResponseEntity<?> resp = api.submitManifest(submitRequest(SRC, DEST, null, "trk"));

        final ArgumentCaptor<BatchTransfer> batch = ArgumentCaptor.forClass(BatchTransfer.class);
        verify(service).submitTransferRequest(batch.capture(), eq(user));
        final List<TransferRequest> requests = batch.getValue().getRequests();
        assertEquals(2, requests.size());
        for (final TransferRequest request : requests) {
            assertEquals("ANON-1", request.getCsvValues().get("pid"));
            assertEquals("ANON_S1", request.getDestinationSubjectLabel());
        }
        assertEquals(2, ((ManifestSubmitResult) resp.getBody()).getItemCount());
    }

    @Test
    public void submitManifest_reportsSkippedRowsByReason() throws Exception {
        when(manifestService.parse(any(), any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", csv("pid", "ANON-1")),
                notFoundRow("S7", "SESS7", ManifestRow.STATUS_SUBJECT_NOT_FOUND),
                notFoundRow("S8", "SESS8", ManifestRow.STATUS_SESSION_NOT_FOUND),
                notFoundRow("S9", "", ManifestRow.STATUS_NO_SESSIONS),
                notFoundRow("S10", "", ManifestRow.STATUS_NO_SESSIONS)));

        final ManifestSubmitResult body =
                (ManifestSubmitResult) api.submitManifest(submitRequest(SRC, DEST, null, "trk")).getBody();

        assertEquals(1, body.getItemCount());
        assertEquals(4, body.getSkippedNotFound());
        assertEquals(Integer.valueOf(1), body.getSkipped().get(ManifestRow.STATUS_SUBJECT_NOT_FOUND));
        assertEquals(Integer.valueOf(1), body.getSkipped().get(ManifestRow.STATUS_SESSION_NOT_FOUND));
        assertEquals(Integer.valueOf(2), body.getSkipped().get(ManifestRow.STATUS_NO_SESSIONS));
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
