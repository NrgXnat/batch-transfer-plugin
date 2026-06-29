package org.nrg.xnatx.plugins.transfer.api;

import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.TransferCapabilities;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.nrg.xnatx.plugins.transfer.service.TransferCapabilitiesService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.annotations.Api;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Slf4j
@Api("Batch Transfer Api")
@RequestMapping(value = "/transfer")
@XapiRestController
public class BatchTransferApi extends AbstractXapiRestController {

    private final BatchTransferService batchTransferService;
    private final TransferCapabilitiesService capabilitiesService;

    protected BatchTransferApi(UserManagementServiceI userManagementService, RoleHolder roleHolder,
                               BatchTransferService batchTransferService, TransferCapabilitiesService capabilitiesService) {
        super(userManagementService, roleHolder);
        this.batchTransferService = batchTransferService;
        this.capabilitiesService = capabilitiesService;
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

        // Phase 1A guardrails for a custom anonymization script (Reimport-only static script).
        final String anonScript = batchTransferRequest.getAnonScript();
        if (StringUtils.isNotBlank(anonScript)) {
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
        }

        batchTransferService.submitTransferRequest(batchTransferRequest, user);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation(value = "Reports per-import anonymization capability and limits for this XNAT build")
    @ApiResponses({ @ApiResponse(code = 200, message = "Capabilities returned") })
    @XapiRequestMapping(value = {"capabilities"}, produces = APPLICATION_JSON_VALUE, method = GET)
    @ResponseBody
    public ResponseEntity<TransferCapabilities> capabilities() {
        return new ResponseEntity<>(capabilitiesService.getCapabilities(), HttpStatus.OK);
    }
}
