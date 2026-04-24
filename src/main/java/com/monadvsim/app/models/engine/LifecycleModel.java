package com.monadvsim.app.models.engine;
import com.monadvsim.app.models.utils.SimulationLogger;

import com.monadvsim.app.models.entities.LifecycleStage;
import com.monadvsim.app.models.entities.LivingAgent;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implements the temperature‑dependent life‑cycle rates and transition probabilities
 * based on the Metzler matrix model.
 */
public class LifecycleModel {

    private final SpeciesParameters params;
    private final double dtDays;          // tick duration in days
    private final ThreadLocalRandom rng = ThreadLocalRandom.current();

    /**
     * @param params        species parameters
     * @param tickMinutes   duration of one simulation tick in minutes
     */
    public LifecycleModel(SpeciesParameters params, double tickMinutes) {
        this.params = params;
        this.dtDays = tickMinutes / (24.0 * 60.0);   // convert minutes to days
    }

    // ------------------------------------------------------------------------
    // Development rates (1/day)
    // ------------------------------------------------------------------------
    public double eggDevelopmentRate(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double val = params.eggDevA * t * t + params.eggDevB * t + params.eggDevC;
        return Math.max(0, val);
    }

    public double larvaDevelopmentRate(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double val = params.larvaDevA * t * t + params.larvaDevB * t + params.larvaDevC;
        return Math.max(0, val);
    }

    public double pupaDevelopmentRate(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double val = params.pupaDevA * t * t + params.pupaDevB * t + params.pupaDevC;
        return Math.max(0, val);
    }

    // ------------------------------------------------------------------------
    // Survival probabilities over the entire stage (0..1)
    // ------------------------------------------------------------------------
    public double eggSurvival(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double exponent = -0.5 * Math.pow((t - params.eggSurvivalMean) / params.eggSurvivalSigma, 2);
        return params.eggSurvivalAmp * Math.exp(exponent);
    }

    public double larvaSurvival(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double exponent = -0.5 * Math.pow((t - params.larvaSurvivalMean) / params.larvaSurvivalSigma, 2);
        return params.larvaSurvivalAmp * Math.exp(exponent);
    }

    public double pupaSurvival(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double exponent = -0.5 * Math.pow((t - params.pupaSurvivalMean) / params.pupaSurvivalSigma, 2);
        return params.pupaSurvivalAmp * Math.exp(exponent);
    }

    // ------------------------------------------------------------------------
    // Adult mortality (1/day)
    // ------------------------------------------------------------------------
    public double adultMortalityRate(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double val = params.adultMortA * t * t + params.adultMortB * t + params.adultMortC;
        return Math.max(0, val);
    }

    // ------------------------------------------------------------------------
    // Fecundity (eggs/female/day)
    // ------------------------------------------------------------------------
    public double fecundityRate(double tempKelvin) {
        double t = tempKelvin - 273.15;
        double term1 = params.fecundityA * Math.exp(params.fecundityB * t);
        double exponent = Math.pow((params.fecundityTmax - t) / params.fecundityC, 2);
        double term2 = Math.exp(params.fecundityB * params.fecundityTmax - exponent);
        return Math.max(0, term1 - term2);
    }

    // ------------------------------------------------------------------------
    // Probabilities per tick (using small‑time approximation)
    // ------------------------------------------------------------------------
    public double transitionProb(double ratePerDay) {
        // Probability of completing the stage in one tick = 1 - exp(-rate * dt)
        // For small rate*dt, this is approximately rate*dt, but we use exact formula.
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    public double adultMortalityProb(double tempKelvin) {
        double mu = adultMortalityRate(tempKelvin);
        return 1.0 - Math.exp(-mu * dtDays);
    }

    // ------------------------------------------------------------------------
    // Host carrying capacity K = 1 + log(1 + L)
    // ------------------------------------------------------------------------
    public double hostCarryingCapacity(double livestockDensity) {
        return 1.0 + Math.log1p(livestockDensity);
    }

    // Effective fecundity (eggs/female/tick) scaled by host availability
    public double effectiveFecundity(double tempKelvin, double livestockDensity) {
        double F = fecundityRate(tempKelvin);
        double K = hostCarryingCapacity(livestockDensity);
        return F * K * dtDays;   // eggs per tick
    }

    // ------------------------------------------------------------------------
    // Stage transition logic for LivingAgent (stochastic)
    // ------------------------------------------------------------------------
    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
        double dL = larvaDevelopmentRate(tempKelvin);
        double pAdvance = transitionProb(dL);
        if (rng.nextDouble() < pAdvance) {
            double survival = larvaSurvival(tempKelvin);
            if (rng.nextDouble() < survival) {
                agent.setStage(LifecycleStage.PUPA);
                agent.setEnergy(0.6);
            } else {
                agent.setAlive(false);
            }
        }
    }
    
    
    public int eggsToLay(double tempKelvin, double hostDensity, ThreadLocalRandom rng) {
        double t = tempKelvin - 273.15;
        double dailyFecundity = fecundityRate(tempKelvin);
        double K = hostCarryingCapacity(hostDensity);
        double expectedEggsPerTick = dailyFecundity * K * dtDays;

        // Stochastic: eggs laid = floor(expected) with probability fractional part
        int eggs = (int) expectedEggsPerTick;
        double remainder = expectedEggsPerTick - eggs;
        if (rng.nextDouble() < remainder) eggs++;

        // SimulationLogger.info("[MODEL] t=%.2f°C, dailyFec=%.2f, K=%.2f → expected=%.3f, laid=%d",
        //    t, dailyFecundity, K, expectedEggsPerTick, eggs);
        return eggs;
    }
    

    public void tryAdvanceFromPupa(LivingAgent agent, double tempKelvin) {
        double dP = pupaDevelopmentRate(tempKelvin);
        double pAdvance = transitionProb(dP);
        if (rng.nextDouble() < pAdvance) {
            double survival = pupaSurvival(tempKelvin);
            if (rng.nextDouble() < survival) {
                agent.setStage(LifecycleStage.ADULT);
                agent.setEnergy(0.9);
            } else {
                agent.setAlive(false);
            }
        }
    }

    public void applyAdultMortality(LivingAgent agent, double tempKelvin) {
        double pDie = adultMortalityProb(tempKelvin);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
        }
    }
}