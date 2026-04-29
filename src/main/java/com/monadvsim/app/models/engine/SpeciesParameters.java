package com.monadvsim.app.models.engine;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Holds all species‑specific parameters for the temperature‑dependent
 * life‑cycle model (Metzler matrix) for Anopheles stephensi.
 * Defaults are based on Mordecai et al. 2013 and related literature.
 */
public class SpeciesParameters {

    // Fecundity: F(T) = a * exp(b*T) - exp(b*Tmax - ((Tmax - T)/c)^2)
    @JsonProperty("fecundity_a")
    public double fecundityA = -0.0099;      // Corrected for realistic peak
    @JsonProperty("fecundity_b")
    public double fecundityB = 0.567;
    @JsonProperty("fecundity_Tmax")
    public double fecundityTmax = 32.0;
    @JsonProperty("fecundity_c")
    public double fecundityC = 2.97;

    // Egg development rate: dE(T) = a*T² + b*T + c
    @JsonProperty("egg_dev_a")
    public double eggDevA = -0.00098;
    @JsonProperty("egg_dev_b")
    public double eggDevB = 0.0502;
    @JsonProperty("egg_dev_c")
    public double eggDevC = -0.348;

    // Larva development rate
    @JsonProperty("larva_dev_a")
    public double larvaDevA = -0.00082;
    @JsonProperty("larva_dev_b")
    public double larvaDevB = 0.0425;
    @JsonProperty("larva_dev_c")
    public double larvaDevC = -0.305;

    // Pupa development rate
    @JsonProperty("pupa_dev_a")
    public double pupaDevA = -0.00074;
    @JsonProperty("pupa_dev_b")
    public double pupaDevB = 0.0398;
    @JsonProperty("pupa_dev_c")
    public double pupaDevC = -0.291;

    // Egg survival: S_E(T) = amp * exp(-0.5 * ((T - mean)/sigma)^2)
    @JsonProperty("egg_survival_amp")
    public double eggSurvivalAmp = 0.94;
    @JsonProperty("egg_survival_mean")
    public double eggSurvivalMean = 26.2;
    @JsonProperty("egg_survival_sigma")
    public double eggSurvivalSigma = 3.5;

    // Larva survival
    @JsonProperty("larva_survival_amp")
    public double larvaSurvivalAmp = 0.88;
    @JsonProperty("larva_survival_mean")
    public double larvaSurvivalMean = 26.8;
    @JsonProperty("larva_survival_sigma")
    public double larvaSurvivalSigma = 3.8;

    // Pupa survival
    @JsonProperty("pupa_survival_amp")
    public double pupaSurvivalAmp = 0.91;
    @JsonProperty("pupa_survival_mean")
    public double pupaSurvivalMean = 26.5;
    @JsonProperty("pupa_survival_sigma")
    public double pupaSurvivalSigma = 3.6;

    // Adult mortality rate: mu_A(T) = a*T² + b*T + c (1/day)
    @JsonProperty("adult_mort_a")
    public double adultMortA = 0.00012;
    @JsonProperty("adult_mort_b")
    public double adultMortB = -0.0018;
    @JsonProperty("adult_mort_c")
    public double adultMortC = 0.055;

    // No‑arg constructor for Jackson deserialisation
    public SpeciesParameters() {}
}