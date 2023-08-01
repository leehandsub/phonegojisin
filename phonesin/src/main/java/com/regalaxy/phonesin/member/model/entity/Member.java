package com.regalaxy.phonesin.member.model.entity;

import com.regalaxy.phonesin.address.model.entity.Address;
import com.regalaxy.phonesin.back.model.BackDto;
import com.regalaxy.phonesin.donation.model.entity.Donation;
import com.regalaxy.phonesin.member.model.MemberDto;
import com.regalaxy.phonesin.rental.model.entity.Rental;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.persistence.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static javax.persistence.CascadeType.ALL;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    @ApiModelProperty(value = "멤버 ID")
    private Long memberId;


    @Column(unique = true)
    @ApiModelProperty(value = "사용자 이메일")
    private String email;

    @ApiModelProperty(value = "사용자 이름")
    private String memberName;

    @ApiModelProperty(value = "비밀번호")
    private String password;

    @ApiModelProperty(value = "휴대폰 번호")
    private String phoneNumber;

    @ApiModelProperty(value = "차상위 계층 여부")
    private Boolean isCha;

    @ApiModelProperty(value = "블랙리스트 여부")
    private Boolean isBlackList;

    @ApiModelProperty(value = "사용자 아이디 삭제 여부")
    private Boolean isDelete;

    @ApiModelProperty(value = "관리자 권한 여부")
    private Boolean isManager;

    @ApiModelProperty(value = "리프레시 토큰")
    private String refreshToken;

    @ApiModelProperty(value = "기부 리스트")
    @OneToMany(mappedBy = "member", cascade = ALL, orphanRemoval = true)
    private List<Donation> donationList = new ArrayList<Donation>();

    @ApiModelProperty(value = "대여 리스트")
    @OneToMany(mappedBy = "member", cascade = ALL, orphanRemoval = true)
    private List<Rental> rentalList = new ArrayList<>();

    @ApiModelProperty(value = "주소 리스트")
    @OneToMany(mappedBy = "member", cascade = ALL, orphanRemoval = true)
    private List<Address> addressList = new ArrayList<>();

    public static MemberDto toDto(Member member) {
        return MemberDto.builder()
                .memberId(member.getMemberId())
                .memberName(member.getMemberName())
                .email(member.getEmail())
                .phoneNumber(member.getPhoneNumber())
                .isCha(member.getIsCha())
                .isManager(member.getIsManager())
                .isBlackList(member.getIsBlackList())
                .isDelete(member.getIsDelete())
                .build();
    }

    public void updateRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}