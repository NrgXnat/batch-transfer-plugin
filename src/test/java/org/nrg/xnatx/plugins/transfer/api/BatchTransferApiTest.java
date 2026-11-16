package org.nrg.xnatx.plugins.transfer.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferRunExistsException;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.XnatItemQueries;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.DestinationLabelsRequest;
import org.nrg.xnatx.plugins.transfer.model.DestinationLabelsResult;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchTransferApi} — the HTTP wiring the service-layer tests
 * ({@code ManifestServiceTest}/{@code PreflightResolverTest}) don't reach: source-project read gate, parse
 * error → status mapping and mode parsing on {@code validate/manifest}, the lookup-free batch checks on
 * {@code POST /xapi/transfer}, and script validation. The
 * {@code ManifestService}/{@code PreflightResolver}/{@code BatchTransferService} collaborators are mocked, so
 * these assert routing/orchestration, not parsing or resolution.
 *
 * <p>{@code getSessionUser()} reads the Spring {@code SecurityContextHolder}; a mock {@link UserI} is set as
 * the authentication principal ({@code Users.getUserPrincipal} returns a {@code UserI} principal verbatim), so
 * no security wiring is stubbed. The controller is a spy so the {@code readableProjectId} seam can be stubbed for
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
    private XnatItemQueries              xnatItems;
    private UserI                        user;
    private BatchTransferApi             api;

    @Before
    public void setUp() {
        service           = mock(BatchTransferService.class);
        capabilities      = mock(TransferCapabilitiesService.class);
        scriptCompiler    = mock(ScriptCompiler.class);
        manifestService   = mock(ManifestService.class);
        preflightResolver = mock(PreflightResolver.class);
        xnatItems         = mock(XnatItemQueries.class);
        user              = mock(UserI.class);

        api = spy(new BatchTransferApi(mock(UserManagementServiceI.class), mock(RoleHolder.class),
                service, capabilities, scriptCompiler, manifestService, preflightResolver, xnatItems));

        final Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Default: the source project is readable and named by its ID. Individual tests override to null for the 403.
        doAnswer(invocation -> invocation.getArgument(0)).when(api).readableProjectId(anyString(), any(UserI.class));
        // The resolver is mocked: fixture rows already carry their statuses, so it hands them back unchanged.
        when(preflightResolver.resolveAll(any(), any(), any(), anyBoolean()))
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
        doReturn(null).when(api).readableProjectId(anyString(), any(UserI.class));
        final ResponseEntity<?> resp = api.validateManifest(validationRequest(SRC, null));
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    /** Rows resolve against the project's ID, so a project named by an alias still finds its subjects. */
    @Test
    public void validateManifest_anAlias_resolvesAgainstTheProjectsId() throws Exception {
        when(manifestService.parse(any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", Collections.<String, String>emptyMap())));
        doReturn(SRC).when(api).readableProjectId("src-alias", user);

        final ResponseEntity<?> resp = api.validateManifest(validationRequest("src-alias", null));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user), eq(false));
    }

    /** A protected project is visible to everyone, but only a user who can read its data passes. */
    @Test
    public void readableProjectId_needsReadAccessToTheProjectsData() {
        doCallRealMethod().when(api).readableProjectId(anyString(), any(UserI.class));
        doReturn(SRC).when(api).projectId("an-alias", user);
        doReturn(null).when(api).projectId("NO_SUCH_PROJECT", user);
        try (MockedStatic<Permissions> permissions = mockStatic(Permissions.class)) {
            permissions.when(() -> Permissions.canRead(user, "xnat:subjectData/project", SRC)).thenReturn(false);
            assertNull("a protected project without data access", api.readableProjectId("an-alias", user));

            permissions.when(() -> Permissions.canRead(user, "xnat:subjectData/project", SRC)).thenReturn(true);
            assertEquals("the project's ID, not the alias", SRC, api.readableProjectId("an-alias", user));

            assertNull("a project that doesn't exist", api.readableProjectId("NO_SUCH_PROJECT", user));
            permissions.verify(() -> Permissions.canRead(any(UserI.class), anyString(), eq("NO_SUCH_PROJECT")), never());
        }
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
        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user), eq(false));
        final ManifestValidationResult body = (ManifestValidationResult) resp.getBody();
        assertEquals(1, body.getSummary().getMatched());
        assertEquals(1, body.getSummary().getNotFound());
    }

    @Test
    public void validateManifest_fileCountFlag_threadedToResolver() throws Exception {
        when(manifestService.parse(any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", Collections.<String, String>emptyMap())));

        final ManifestValidationRequest req = validationRequest(SRC, null);
        req.setFileCount(true);
        final ResponseEntity<?> resp = api.validateManifest(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user), eq(true));
    }

    @Test
    public void validateManifestUpload_invalidMode_returns400() throws Exception {
        final ResponseEntity<?> resp = api.validateManifestUpload(
                mock(MultipartFile.class), SRC, "NotAMode", null, null);
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

        final ResponseEntity<?> resp = api.validateManifestUpload(file, SRC, null, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    public void validateManifest_summaryCountsEntriesAndRowsByStatus() throws Exception {
        final ManifestRow sessionA = matchedRow("S1", "SESS_A", "E1", csv("pid", "ANON-1"));
        final ManifestRow sessionB = matchedRow("S1", "SESS_B", "E2", csv("pid", "ANON-1"));
        when(manifestService.parse(any(), any())).thenReturn(result(true, sessionA, sessionB,
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
    public void validateManifest_modeIsAcceptedAndIgnored() throws Exception {
        when(manifestService.parse(any(), any())).thenReturn(result(true,
                matchedRow("S1", "SESS1", "E1", Collections.<String, String>emptyMap())));
        final ManifestValidationRequest req = validationRequest(SRC, null);
        req.setMode(TransferMode.CLONE);

        final ResponseEntity<?> resp = api.validateManifest(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(manifestService).parse(req.getManifestCsv(), null);
        verify(preflightResolver).resolveAll(eq(SRC), any(List.class), eq(user), eq(false));
    }

    // ---- POST /xapi/transfer batch checks --------------------------------------------------------------

    @Test
    public void submit_distinctItems_areSubmitted() {
        final TransferRequest first = reimport("E1");
        first.setDestinationSessionLabel("STUDY2_A");
        final TransferRequest second = reimport("E2");
        second.setDestinationSessionLabel("STUDY2_B");
        second.setDestinationSubjectLabel("ANON-042");

        final ResponseEntity<String> resp = api.submit(batch(first, second));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(service).submitTransferRequest(any(BatchTransfer.class), eq(user));
    }

    @Test
    public void submit_aSourceProjectTheUserCanRead_isSubmitted() {
        final BatchTransfer batch = batch(reimport("E1"));
        batch.setSourceProject(SRC);

        final ResponseEntity<String> resp = api.submit(batch);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(api).readableProjectId(SRC, user);
        verify(service).submitTransferRequest(batch, user);
    }

    /** The submit carries the project's ID: items record their project by ID, so an alias would match none. */
    @Test
    public void submit_aSourceProjectAlias_isPassedOnAsTheProjectsId() {
        doReturn(SRC).when(api).readableProjectId("src-alias", user);
        final BatchTransfer batch = batch(reimport("E1"));
        batch.setSourceProject("src-alias");

        final ResponseEntity<String> resp = api.submit(batch);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(SRC, batch.getSourceProject());
        verify(service).submitTransferRequest(batch, user);
    }

    @Test
    public void submit_aSourceProjectTheUserCannotRead_returns403() {
        doReturn(null).when(api).readableProjectId(anyString(), any(UserI.class));
        final BatchTransfer batch = batch(reimport("E1"));
        batch.setSourceProject(SRC);

        final ResponseEntity<String> resp = api.submit(batch);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("You do not have read access to project " + SRC + ".", resp.getBody());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submit_theSameItemTwice_returns400NamingIt() {
        final ResponseEntity<String> resp = api.submit(batch(reimport("E1"), reimport("E2"), reimport("E1")));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("The request lists the same item more than once: E1.", resp.getBody());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submit_twoReimportItemsRoutedToOneSessionLabel_returns400() {
        final TransferRequest first = reimport("E1");
        first.setDestinationSessionLabel("STUDY2_A");
        final TransferRequest second = reimport("E2");
        second.setDestinationSessionLabel("STUDY2_A");

        final ResponseEntity<String> resp = api.submit(batch(first, second, reimport("E3")));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody(), resp.getBody().contains("'STUDY2_A' (E1, E2)"));
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submit_anInvalidRoutingLabel_returns400() {
        final TransferRequest subject = reimport("E1");
        subject.setDestinationSubjectLabel("ANON 042");
        final TransferRequest session = reimport("E2");
        session.setDestinationSessionLabel("STUDY2.042");

        final ResponseEntity<String> resp = api.submit(batch(subject, session));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody(), resp.getBody().contains("E1 destination_subject_label 'ANON 042'"));
        assertTrue(resp.getBody(), resp.getBody().contains("E2 destination_session_label 'STUDY2.042'"));
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submit_blankRoutingLabelsMeanDeriveAndPass() {
        final TransferRequest first = reimport("E1");
        first.setDestinationSessionLabel("");
        final TransferRequest second = reimport("E2");
        second.setDestinationSessionLabel("");

        assertEquals(HttpStatus.OK, api.submit(batch(first, second)).getStatusCode());
    }

    @Test
    public void submit_routingLabelsOnShareItemsAreNotChecked() {
        final TransferRequest first = new TransferRequest(DEST, "E1", TransferMode.SHARE);
        first.setDestinationSessionLabel("SAME");
        final TransferRequest second = new TransferRequest(DEST, "E2", TransferMode.SHARE);
        second.setDestinationSessionLabel("SAME");
        second.setDestinationSubjectLabel("not a label");

        assertEquals(HttpStatus.OK, api.submit(batch(first, second)).getStatusCode());
    }

    @Test
    public void submit_itemsWithoutAnId_are400() {
        final ResponseEntity<String> resp = api.submit(batch(reimport("E1"), reimport(null), reimport(" ")));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("Every item needs an id; 2 items have none.", resp.getBody());
        verify(service, never()).submitTransferRequest(any(), any());
    }

    @Test
    public void submit_aTrackingIdThatAlreadyHasARun_is409() {
        doThrow(new BatchTransferRunExistsException("trk")).when(service).submitTransferRequest(any(), any());
        final ResponseEntity<String> resp = api.submit(batch(reimport("E1")));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("A transfer with tracking id trk already exists.", resp.getBody());
    }

    @Test
    public void submit_aSimultaneousSubmitWithTheSameTrackingId_is409() {
        doThrow(new RuntimeException("insert failed", new SQLException("duplicate key", "23505")))
                .when(service).submitTransferRequest(any(), any());
        assertEquals(HttpStatus.CONFLICT, api.submit(batch(reimport("E1"))).getStatusCode());
    }

    @Test
    public void submit_anotherFailureIsRethrown() {
        doThrow(new IllegalStateException("broker down")).when(service).submitTransferRequest(any(), any());
        assertThrows(IllegalStateException.class, () -> api.submit(batch(reimport("E1"))));
    }

    @Test
    public void submit_aLongListIsShortened() {
        final List<TransferRequest> requests = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            requests.add(reimport("E" + i));
            requests.add(reimport("E" + i));
        }

        final ResponseEntity<String> resp = api.submit(new BatchTransfer(requests, "trk"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody(), resp.getBody().endsWith("E9, E10 and 2 more."));
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

    // ---- fixtures ------------------------------------------------------------------------------------

    private static TransferRequest reimport(final String id) {
        return new TransferRequest(DEST, id, TransferMode.REIMPORT);
    }

    private static BatchTransfer batch(final TransferRequest... requests) {
        return new BatchTransfer(new ArrayList<>(Arrays.asList(requests)), "trk");
    }

    private static ManifestValidationRequest validationRequest(final String sourceProject, final String anonScript) {
        final ManifestValidationRequest req = new ManifestValidationRequest();
        req.setSourceProject(sourceProject);
        req.setManifestCsv("source_subject_label,source_session_label\nS1,SESS1\n");
        req.setAnonScript(anonScript);
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

    // ---- validate/destination_labels ------------------------------------------------------------------------

    @Test
    public void destinationLabels_listsTheTakenLabelsSorted_andSkipsBlanksAndRepeats() {
        doReturn(DEST).when(api).readableProjectId("dest-alias", user);
        when(xnatItems.takenExperimentLabels(eq(DEST), any())).thenReturn(new HashSet<>(Arrays.asList("S_2", "S_1")));

        final ResponseEntity<?> resp = api.validateDestinationLabels(
                new DestinationLabelsRequest("dest-alias", Arrays.asList("S_1", "", "S_2", "S_1", null, "S_3")));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        final DestinationLabelsResult result = (DestinationLabelsResult) resp.getBody();
        assertEquals("the alias resolves to the project's ID", DEST, result.getDestinationProject());
        assertEquals(Arrays.asList("S_1", "S_2"), result.getExistingSessionLabels());
        verify(xnatItems).takenExperimentLabels(DEST, new LinkedHashSet<>(Arrays.asList("S_1", "S_2", "S_3")));
    }

    @Test
    public void destinationLabels_withoutADestination_is400() {
        assertEquals(HttpStatus.BAD_REQUEST, api.validateDestinationLabels(
                new DestinationLabelsRequest(" ", Collections.singletonList("S_1"))).getStatusCode());
        verify(xnatItems, never()).takenExperimentLabels(any(), any());
    }

    @Test
    public void destinationLabels_forAProjectTheCallerCantRead_is403() {
        doReturn(null).when(api).readableProjectId(anyString(), any(UserI.class));
        assertEquals(HttpStatus.FORBIDDEN, api.validateDestinationLabels(
                new DestinationLabelsRequest(DEST, Collections.singletonList("S_1"))).getStatusCode());
        verify(xnatItems, never()).takenExperimentLabels(any(), any());
    }

    @Test
    public void destinationLabels_overTheLimit_is413() {
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i <= BatchTransferApi.MAX_DESTINATION_LABELS; i++) {
            labels.add("S_" + i);
        }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,
                api.validateDestinationLabels(new DestinationLabelsRequest(DEST, labels)).getStatusCode());
        verify(xnatItems, never()).takenExperimentLabels(any(), any());
    }

    @Test
    public void destinationLabels_overTheLimitForAProjectTheCallerCantRead_is403() {
        doReturn(null).when(api).readableProjectId(anyString(), any(UserI.class));
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i <= BatchTransferApi.MAX_DESTINATION_LABELS; i++) {
            labels.add("S_" + i);
        }
        assertEquals(HttpStatus.FORBIDDEN,
                api.validateDestinationLabels(new DestinationLabelsRequest(DEST, labels)).getStatusCode());
    }

    @Test
    public void destinationLabels_withNoLabels_isAnEmptyList() {
        when(xnatItems.takenExperimentLabels(eq(DEST), any())).thenReturn(new HashSet<>());
        final ResponseEntity<?> resp = api.validateDestinationLabels(new DestinationLabelsRequest(DEST, null));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(((DestinationLabelsResult) resp.getBody()).getExistingSessionLabels().isEmpty());
    }
}
