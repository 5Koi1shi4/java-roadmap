package com.example.campusmarket.dispute.application;

import java.util.UUID;

/** 案件逻辑 ACL；物理对象读取前必须重新调用。 */
public interface EvidenceCaseAccess {
    boolean canAttach(String caseType, UUID caseId, UUID actorId);
    boolean canRead(String caseType, UUID caseId, UUID actorId);
}
