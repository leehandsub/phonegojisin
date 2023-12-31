package com.regalaxy.phonesin.phone.model;

import lombok.*;

@Getter
@Setter
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PhoneApplyDto {
    private Long phoneId;
    private Long donationId;
    private String serialNumber;
    private Long modelId;
    private boolean isY2K;
    private boolean isClimateHumidity;
    private boolean isHomecam;
}
