package com.monadvsim.app.models.engine;
import com.monadvsim.app.models.utils.SimulationLogger;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Holds all species-specific parameters for the Metzler matrix life-cycle model.
 * Can be loaded from a JSON file.
 */
public class SpeciesParameters {

    // Fecundity: F(T) = a * exp(b*T) - exp(b*Tmax - ((Tmax - T)/c)^2)
    @JsonProperty("fecundity_a")
    public double fecundityA = 0.378;
    @JsonProperty("fecundity_b")
    public double fecundityB = 0.173;
    @JsonProperty("fecundity_Tmax")
    public double fecundityTmax = 40.0;
    @JsonProperty("fecundity_c")
    public double fecundityC = 2.97;

    // Egg development rate: dE(T) = max(0, a*T^2 + b*T + c)
    @JsonProperty("egg_dev_a")
    public double eggDevA = -0.0009;
    @JsonProperty("egg_dev_b")
    public double eggDevB = 0.048;
    @JsonProperty("egg_dev_c")
    public double eggDevC = -0.345;

    // Larva development rate: dL(T)
    @JsonProperty("larva_dev_a")
    public double larvaDevA = -0.0007;
    @JsonProperty("larva_dev_b")
    public double larvaDevB = 0.039;
    @JsonProperty("larva_dev_c")
    public double larvaDevC = -0.32;

    // Pupa development rate: dP(T)
    @JsonProperty("pupa_dev_a")
    public double pupaDevA = -0.0005;
    @JsonProperty("pupa_dev_b")
    public double pupaDevB = 0.026;
    @JsonProperty("pupa_dev_c")
    public double pupaDevC = -0.2;

    // Egg survival: S_E(T) = amp * exp(-0.5 * ((T - mean)/sigma)^2)
    @JsonProperty("egg_survival_amp")
    public double eggSurvivalAmp = 1.01;
    @JsonProperty("egg_survival_mean")
    public double eggSurvivalMean = 24.5;
    @JsonProperty("egg_survival_sigma")
    public double eggSurvivalSigma = 4.8;

    // Larva survival
    @JsonProperty("larva_survival_amp")
    public double larvaSurvivalAmp = 0.95;
    @JsonProperty("larva_survival_mean")
    public double larvaSurvivalMean = 27.0;
    @JsonProperty("larva_survival_sigma")
    public double larvaSurvivalSigma = 3.5;

    // Pupa survival
    @JsonProperty("pupa_survival_amp")
    public double pupaSurvivalAmp = 0.93;
    @JsonProperty("pupa_survival_mean")
    public double pupaSurvivalMean = 26.8;
    @JsonProperty("pupa_survival_sigma")
    public double pupaSurvivalSigma = 3.8;

    // Adult mortality rate: mu_A(T) = max(0, a*T^2 + b*T + c) (1/day)
    @JsonProperty("adult_mort_a")
    public double adultMortA = -0.00065;
    @JsonProperty("adult_mort_b")
    public double adultMortB = 0.0364;
    @JsonProperty("adult_mort_c")
    public double adultMortC = -0.4882;

    // Host carrying capacity scaling: K = 1 + log(1 + L)
    // L is livestock density (or population density). We'll keep generic.
    // No parameters needed for that formula.

    /**
     * No‑arg constructor required for Jackson deserialisation.
     */
    public SpeciesParameters() {
        // defaults are already set via field initialisers
    }
}