package org.nrg.xnatx.plugins.transfer.api;

import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.TransferCapabilities;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.nrg.xnatx.plugins.transfer.service.TransferCapabilitiesService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.annotations.Api;
import org.springframework.web.bind.annotation.ResponseBody;

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
                    @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = {""}, consumes = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<Void> submit(@RequestBody BatchTransfer batchTransferRequest) {
        if(batchTransferRequest.getRequests() == null || batchTransferRequest.getRequests().size() == 0){
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        batchTransferService.submitTransferRequest(batchTransferRequest, getSessionUser());
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
