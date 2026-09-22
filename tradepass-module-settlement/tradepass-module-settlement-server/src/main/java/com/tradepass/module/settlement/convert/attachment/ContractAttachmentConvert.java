package com.tradepass.module.settlement.convert.attachment;

import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.FileRespDTO;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface ContractAttachmentConvert {
    ContractAttachmentConvert INSTANCE = Mappers.getMapper(ContractAttachmentConvert.class);

    @Mapping(target = "data", source = "fileData")
    FileRespDTO toFileRespDTO(ContractAttachmentDO source);
}
