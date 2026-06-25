package org.nrg.xnatx.plugins.transfer.api;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnatx.plugins.transfer.jms.preferences.BatchTransferQueuePrefsBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;

/**
 * Admin REST API backing the "Site-wide JMS Queue Settings" panel (defined in the batchTransfer
 * spawner {@code site-settings.yaml}). GET returns the current Batch Transfer queue concurrency
 * preferences as a map; POST updates them via {@link BatchTransferQueuePrefsBean#setBatch}. Modeled
 * on container-service's {@code QueueSettingsRestApi}, but restricted to site administrators.
 *
 * <p>The preference bean extends a {@code HashMap<String,Object>}, so it serializes directly as the
 * GET response; the spawner form's field {@code name}s match the preference keys
 * ({@code reimportConcurrencyMin}/{@code reimportConcurrencyMax}).
 */
@Api("Batch Transfer JMS Queue Settings API")
@XapiRestController
@RequestMapping(value = "/batch_transfer/jms_queues")
@Slf4j
public class BatchTransferQueueSettingsApi extends AbstractXapiRestController {

    private final BatchTransferQueuePrefsBean queuePrefs;

    @Autowired
    public BatchTransferQueueSettingsApi(final BatchTransferQueuePrefsBean queuePrefs,
                                         final UserManagementServiceI userManagementService,
                                         final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.queuePrefs = queuePrefs;
    }

    @ApiOperation(value = "Returns the Batch Transfer JMS queue concurrency settings.", response = Map.class, responseContainer = "Map")
    @ApiResponses({@ApiResponse(code = 200, message = "Queue settings successfully retrieved."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Administrator access required."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(produces = {MediaType.APPLICATION_JSON_VALUE}, method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public Map<String, Object> getQueueSettings() {
        return queuePrefs;
    }

    @ApiOperation(value = "Sets the Batch Transfer JMS queue concurrency settings.")
    @ApiResponses({@ApiResponse(code = 200, message = "Queue settings successfully set."),
            @ApiResponse(code = 400, message = "Invalid input."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Administrator access required."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE},
            method = RequestMethod.POST, restrictTo = Admin)
    @ResponseBody
    public void setQueueSettings(@ApiParam(value = "The queue settings properties to set.", required = true)
                                 @RequestBody final Map<String, String> properties) throws ClientException, ServerException {
        try {
            queuePrefs.setBatch(properties);
        } catch (InvalidPreferenceName e) {
            throw new ClientException(e.getMessage());
        } catch (Exception e) {
            throw new ServerException(e.getMessage());
        }
    }
}
