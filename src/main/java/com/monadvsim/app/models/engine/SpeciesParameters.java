package com.monadvsim.app.models.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/**
 * Species‑specific parameters for the temperature‑dependent life‑cycle model.
 */
public class SpeciesParameters implements Serializable {

    private static final long serialVersionUID = 20250501L;

    @JsonProperty("egg_dev_rho")
    public double eggDev_rho = 0.01107;
    @JsonProperty("egg_dev_k")
    public double eggDev_k = 41.54210;
    @JsonProperty("egg_dev_Delta")
    public double eggDev_Delta = 0.98086;
    @JsonProperty("egg_dev_lambda")
    public double eggDev_lambda = -1.14079;

    @JsonProperty("larva_dev_a")
    public double larvaDev_a = 3.285e-5;
    @JsonProperty("larva_dev_Tmin")
    public double larvaDev_Tmin = 17.32;
    @JsonProperty("larva_dev_Tmax")
    public double larvaDev_Tmax = 40.88;
    @JsonProperty("larva_dev_m")
    public double larvaDev_m = 2.169;

    @JsonProperty("pupa_dev_rho")
    public double pupaDev_rho = 0.0096287;
    @JsonProperty("pupa_dev_k")
    public double pupaDev_k = 41.1843270;
    @JsonProperty("pupa_dev_Delta")
    public double pupaDev_Delta = 0.9295908;
    @JsonProperty("pupa_dev_lambda")
    public double pupaDev_lambda = -1.1507102;

    @JsonProperty("egg_mort_b1")
    public double eggMort_b1 = 3.572876;
    @JsonProperty("egg_mort_b2")
    public double eggMort_b2 = -0.323474;
    @JsonProperty("egg_mort_b3")
    public double eggMort_b3 = 0.004941;

    @JsonProperty("larva_mort_b1")
    public double larvaMort_b1 = 3.572876;
    @JsonProperty("larva_mort_b2")
    public double larvaMort_b2 = -0.323474;
    @JsonProperty("larva_mort_b3")
    public double larvaMort_b3 = 0.004941;

    @JsonProperty("pupa_mort_b1")
    public double pupaMort_b1 = 5.882576;
    @JsonProperty("pupa_mort_b2")
    public double pupaMort_b2 = -0.578528;
    @JsonProperty("pupa_mort_b3")
    public double pupaMort_b3 = 0.009458;

    @JsonProperty("fecundity_rmax")
    public double fecundity_rmax = 1.571304;
    @JsonProperty("fecundity_Topt")
    public double fecundity_Topt = 32.908160;
    @JsonProperty("fecundity_c")
    public double fecundity_c = -0.007832;

    @JsonProperty("adult_mortality_per_day")
    public double adultMortalityPerDay = 1.0 / 240.0;
    @JsonProperty("adult_mort_b1") public double adultMort_b1 = 0;
    @JsonProperty("adult_mort_b2") public double adultMort_b2 = 0;
    @JsonProperty("adult_mort_b3") public double adultMort_b3 = 0;

    @JsonProperty("sex_ratio")
    public double sexRatio = 0.5;

    @JsonProperty("host_carrying_capacity_base")
    public double hostCarryingCapacityBase = 1.0;

    public SpeciesParameters() {}
}