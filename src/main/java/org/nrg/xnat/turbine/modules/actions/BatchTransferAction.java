// Copyright 2021 Radiologics, INC
// Developer: Timothy Olsen <tim@radiologics.com

package org.nrg.xnat.turbine.modules.actions;

import lombok.extern.slf4j.Slf4j;
import org.apache.turbine.util.RunData;
import org.nrg.xdat.turbine.modules.actions.ListingAction;

@SuppressWarnings("unused")
@Slf4j
public class BatchTransferAction  extends ListingAction {

    /* (non-Javadoc)
     * @see org.nrg.xdat.turbine.modules.actions.ListingAction#getDestinationScreenName(org.apache.turbine.util.RunData)
     */
    @Override
    public String getDestinationScreenName(RunData data) {
        return "XDATScreen_batch_transfer.vm";
    }

}