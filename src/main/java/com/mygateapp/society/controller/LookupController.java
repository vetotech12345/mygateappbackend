package com.mygateapp.society.controller;

import com.mygateapp.complaints.entity.Complaint;
import com.mygateapp.deliveries.enums.DeliveryCategory;
import com.mygateapp.domesticstaff.entity.DomesticStaff;
import com.mygateapp.exception.ApiException;
import com.mygateapp.notices.entity.Notice;
import com.mygateapp.security.AccessService;
import com.mygateapp.society.dto.SocietyDtos.LookupItem;
import com.mygateapp.society.dto.SocietyDtos;
import com.mygateapp.society.entity.SocietySettings;
import com.mygateapp.society.service.SocietyService;
import com.mygateapp.visitors.enums.VisitorEnums.VisitorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config")
@Tag(name = "Reference data", description = "Categories and rules used by app forms")
public class LookupController {

    private static final List<String> DEFAULT_DELIVERY_COMPANIES =
            List.of("Swiggy", "Zomato", "Amazon", "Flipkart", "Blinkit", "Zepto", "BigBasket", "Dunzo", "Other");

    private final AccessService access;
    private final SocietyService societyService;

    public LookupController(AccessService access, SocietyService societyService) {
        this.access = access;
        this.societyService = societyService;
    }

    @GetMapping("/lookups")
    @Transactional
    @Operation(summary = "Visitor types, delivery categories (with the society's approval rules), staff / complaint / "
            + "notice categories, visit purposes and invite limits for the caller's society")
    public SocietyDtos.LookupsResponse lookups() {
        SocietySettings settings;
        try {
            settings = societyService.settingsFor(access.currentSocietyId());
        } catch (ApiException noSocietyContext) {
            settings = new SocietySettings();
            settings.setVisitPurposes(SocietySettings.DEFAULT_VISIT_PURPOSES);
        }
        SocietySettings rules = settings;
        return new SocietyDtos.LookupsResponse(
                Arrays.stream(VisitorType.values()).map(t -> LookupItem.of(t.name(), t.label())).toList(),
                Arrays.stream(DeliveryCategory.values())
                        .map(c -> new LookupItem(c.name(), c.label(), rules.deliveryRequiresApproval(c)))
                        .toList(),
                Arrays.stream(DomesticStaff.Category.values()).map(c -> LookupItem.of(c.name(), c.label())).toList(),
                Arrays.stream(Complaint.Category.values()).map(c -> LookupItem.of(c.name(), c.label())).toList(),
                Arrays.stream(Notice.Category.values()).map(c -> LookupItem.of(c.name(), c.label())).toList(),
                rules.getVisitPurposes().isEmpty() ? SocietySettings.DEFAULT_VISIT_PURPOSES : rules.getVisitPurposes(),
                rules.getDeliveryCompanies().isEmpty() ? DEFAULT_DELIVERY_COMPANIES : rules.getDeliveryCompanies(),
                rules.getMaxInviteValidityHours(),
                rules.getApprovalTimeoutSeconds());
    }
}
