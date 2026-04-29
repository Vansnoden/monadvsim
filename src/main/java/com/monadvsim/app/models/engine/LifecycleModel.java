package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.LifecycleStage;
import com.monadvsim.app.models.entities.LivingAgent;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Temperature‑dependent life‑cycle rates and transition probabilities
 * for Anopheles stephensi, based on Mordecai et al. 2013 and related literature.
 * All temperature‑sensitive functions accept Kelvin and convert to Celsius.
 */
public class LifecycleModel {

    private final SpeciesParameters params;
    private final double dtDays;          // tick duration in days
    private final ThreadLocalRandom rng = ThreadLocalRandom.current();

    public LifecycleModel(SpeciesParameters params, double tickMinutes) {
        this.params = params;
        this.dtDays = tickMinutes / (24.0 * 60.0);
        SimulationLogger.info("[Lifecycle] dtDays = %.6f days (tickMinutes = %.1f)", dtDays, tickMinutes);
    }

    // ------------------------------------------------------------------------
    // Development rates (1/day) – quadratic: a*T² + b*T + c, T in °C
    // ------------------------------------------------------------------------
    private double celsius(double kelvin) {
        return kelvin - 273.15;
    }

    public double eggDevelopmentRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.eggDevA * t * t + params.eggDevB * t + params.eggDevC;
        return Math.max(0, val);
    }

    public double larvaDevelopmentRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.larvaDevA * t * t + params.larvaDevB * t + params.larvaDevC;
        val = Math.max(0, val);
        if (val > 1.0) {
            SimulationLogger.warning("[Lifecycle] High larval development rate %.3f at t=%.2f°C", val, t);
        }
        return val;
    }

    public double pupaDevelopmentRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.pupaDevA * t * t + params.pupaDevB * t + params.pupaDevC;
        return Math.max(0, val);
    }

    // ------------------------------------------------------------------------
    // Survival probabilities over the entire stage (0..1) – Gaussian
    // ------------------------------------------------------------------------
    public double eggSurvival(double tempKelvin) {
        double t = celsius(tempKelvin);
        double exponent = -0.5 * Math.pow((t - params.eggSurvivalMean) / params.eggSurvivalSigma, 2);
        return params.eggSurvivalAmp * Math.exp(exponent);
    }

    public double larvaSurvival(double tempKelvin) {
        double t = celsius(tempKelvin);
        double exponent = -0.5 * Math.pow((t - params.larvaSurvivalMean) / params.larvaSurvivalSigma, 2);
        return params.larvaSurvivalAmp * Math.exp(exponent);
    }

    public double pupaSurvival(double tempKelvin) {
        double t = celsius(tempKelvin);
        double exponent = -0.5 * Math.pow((t - params.pupaSurvivalMean) / params.pupaSurvivalSigma, 2);
        return params.pupaSurvivalAmp * Math.exp(exponent);
    }

    // ------------------------------------------------------------------------
    // Adult mortality (1/day) – quadratic: a*T² + b*T + c
    // ------------------------------------------------------------------------
    public double adultMortalityRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.adultMortA * t * t + params.adultMortB * t + params.adultMortC;
        return Math.max(0, val);
    }

    // ------------------------------------------------------------------------
    // Fecundity (eggs/female/day) – temperature‑peaked asymmetric function
    // F(T) = a*exp(b*T) - exp(b*Tmax - ((Tmax - T)/c)²)
    // ------------------------------------------------------------------------
    public double fecundityRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double term1 = params.fecundityA * Math.exp(params.fecundityB * t);
        double exponent = Math.pow((params.fecundityTmax - t) / params.fecundityC, 2);
        double term2 = Math.exp(params.fecundityB * params.fecundityTmax - exponent);
        return Math.max(0, term1 - term2);
    }

    // ------------------------------------------------------------------------
    // Host carrying capacity: K = 1 + log(1 + L), where L is livestock density.
    // ------------------------------------------------------------------------
    public double hostCarryingCapacity(double livestockDensity) {
        return 1.0 + Math.log1p(livestockDensity);
    }

    // Effective fecundity (eggs/female/tick) scaled by host availability
    public double effectiveFecundity(double tempKelvin, double livestockDensity) {
        double F = fecundityRate(tempKelvin);
        double K = hostCarryingCapacity(livestockDensity);
        return F * K * dtDays;
    }

    // ------------------------------------------------------------------------
    // Probabilities per tick (exact Poisson process)
    // ------------------------------------------------------------------------
    public double transitionProb(double ratePerDay) {
        // Probability of completing the stage in one tick = 1 - exp(-rate * dt)
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    public double adultMortalityProb(double tempKelvin) {
        double mu = adultMortalityRate(tempKelvin);
        return 1.0 - Math.exp(-mu * dtDays);
    }

    // ------------------------------------------------------------------------
    // Stage transition logic for LivingAgent (stochastic)
    // ------------------------------------------------------------------------
    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
        double dL = larvaDevelopmentRate(tempKelvin);
        double pAdvance = transitionProb(dL);

        if (pAdvance > 0.1) {
            SimulationLogger.info("[Lifecycle] High larval advancement: dL=%.4f, p=%.4f, dtDays=%.6f, temp=%.2fK",
                                  dL, pAdvance, dtDays, tempKelvin);
        }

        if (rng.nextDouble() < pAdvance) {
            double survival = larvaSurvival(tempKelvin);
            if (rng.nextDouble() < survival) {
                agent.setStage(LifecycleStage.PUPA);
                agent.setEnergy(0.6);
                SimulationLogger.info("[SUCCESS] %s → PUPA at age %d, temp=%.2fK, pAdv=%.6f, survival=%.3f",
                                      agent.getId(), agent.getAge(), tempKelvin, pAdvance, survival);
            } else {
                agent.setAlive(false);
                SimulationLogger.info("[DEATH] %s died during pupation at age %d", agent.getId(), agent.getAge());
            }
        }
    }

    public void tryAdvanceFromPupa(LivingAgent agent, double tempKelvin) {
        double dP = pupaDevelopmentRate(tempKelvin);
        double pAdvance = transitionProb(dP);

        if (pAdvance > 0.1) {
            SimulationLogger.info("[Lifecycle] High pupal advancement: dP=%.4f, p=%.4f, dtDays=%.6f, temp=%.2fK",
                                  dP, pAdvance, dtDays, tempKelvin);
        }

        if (rng.nextDouble() < pAdvance) {
            double survival = pupaSurvival(tempKelvin);
            if (rng.nextDouble() < survival) {
                agent.setStage(LifecycleStage.ADULT);
                agent.setEnergy(0.9);
                SimulationLogger.info("[SUCCESS] %s → ADULT at age %d, temp=%.2fK, pAdv=%.6f, survival=%.3f",
                                      agent.getId(), agent.getAge(), tempKelvin, pAdvance, survival);
            } else {
                agent.setAlive(false);
                SimulationLogger.info("[DEATH] %s died during emergence at age %d", agent.getId(), agent.getAge());
            }
        }
    }

    /**
     * Stochastic egg laying for an adult female.
     * Returns the number of eggs to deposit into a nearby water tank.
     */
    public int eggsToLay(double tempKelvin, double hostDensity, ThreadLocalRandom rng) {
        double dailyFecundity = fecundityRate(tempKelvin);
        double K = hostCarryingCapacity(hostDensity);
        double expectedEggsPerTick = dailyFecundity * K * dtDays;

        int eggs = (int) expectedEggsPerTick;
        double remainder = expectedEggsPerTick - eggs;
        if (rng.nextDouble() < remainder) eggs++;

        // Cap at a biologically plausible maximum (e.g., 50 eggs per tick)
        eggs = Math.min(eggs, 50);
        return eggs;
    }

    public void applyAdultMortality(LivingAgent agent, double tempKelvin) {
        double pDie = adultMortalityProb(tempKelvin);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
        }
    }
}