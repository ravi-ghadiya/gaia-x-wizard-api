/*
 * Copyright (c) 2023 | smartSense
 */

package eu.gaiax.wizard.dao.repository.data_master;

import com.smartsensesolutions.java.commons.base.repository.BaseRepository;
import eu.gaiax.wizard.dao.entity.data_master.SpdxLicenseMaster;
import org.springframework.stereotype.Repository;


/**
 * The interface Standard type master repository.
 */
@Repository
public interface SpdxLicenseMasterRepository extends BaseRepository<SpdxLicenseMaster, String> {

}
