package com.example.campusmarket.warranty.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 卖家应承担的维修补偿或退款义务。 */
public final class SellerObligation {
    public enum Status { AWAITING_FUNDING, PARTIALLY_FUNDED, FUNDED, CANCELLED }
    public enum RestrictionStatus { NONE, RESTRICTED }
    private final UUID id; private final UUID warrantyCaseId; private final UUID sellerId;
    private final String businessKey; private final long amountFen; private final Instant fundingDeadline;
    private long fundedAmountFen; private Status status; private RestrictionStatus restrictionStatus;
    public SellerObligation(UUID id, UUID warrantyCaseId, UUID sellerId, String businessKey, long amountFen, Instant fundingDeadline) {
        this.id=Objects.requireNonNull(id); this.warrantyCaseId=Objects.requireNonNull(warrantyCaseId); this.sellerId=Objects.requireNonNull(sellerId);
        if (businessKey==null||businessKey.isBlank()) throw new IllegalArgumentException("义务业务键不能为空");
        if (amountFen<=0) throw new IllegalArgumentException("义务金额必须为正数");
        this.businessKey=businessKey; this.amountFen=amountFen; this.fundingDeadline=Objects.requireNonNull(fundingDeadline);
        this.status=Status.AWAITING_FUNDING; this.restrictionStatus=RestrictionStatus.RESTRICTED;
    }
    public boolean fund(long amount) { if (amount<=0) throw new IllegalArgumentException("筹资金额必须为正数"); if (status==Status.CANCELLED) return false;
        long next=Math.addExact(fundedAmountFen, amount); if (next>amountFen) throw new IllegalArgumentException("筹资金额超出义务");
        fundedAmountFen=next; status=next==amountFen?Status.FUNDED:Status.PARTIALLY_FUNDED; if (status==Status.FUNDED) restrictionStatus=RestrictionStatus.NONE; return true; }
    public void expire(Instant now) { Objects.requireNonNull(now); if (status!=Status.FUNDED && now.compareTo(fundingDeadline)>=0) { status=Status.CANCELLED; restrictionStatus=RestrictionStatus.RESTRICTED; } }
    public UUID id(){return id;} public UUID warrantyCaseId(){return warrantyCaseId;} public UUID sellerId(){return sellerId;} public String businessKey(){return businessKey;}
    public long obligationAmountFen(){return amountFen;} public long fundedAmountFen(){return fundedAmountFen;} public Instant fundingDeadline(){return fundingDeadline;}
    public Status status(){return status;} public RestrictionStatus restrictionStatus(){return restrictionStatus;}
}
