package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.LifecycleStage;
import com.monadvsim.app.models.entities.LivingAgent;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Temperature‑dependent life‑cycle rates and transition probabilities
 * for Anopheles stephensi, based on Mordecai et al. 2013.
 * 
 * Implements the ODE matrix formulation:
 *   dX/dt = development_in - (development_out + mortality) * X
 * 
 * Mortality for immature stages is derived from Gaussian survival
 * and development rates: μ = -ln(S) * r.
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
    // Temperature conversion
    // ------------------------------------------------------------------------
    private double celsius(double kelvin) {
        return kelvin - 273.15;
    }

    // ------------------------------------------------------------------------
    // Development rates (1/day) – quadratic: a*T² + b*T + c, T in °C
    // ------------------------------------------------------------------------
    public double eggDevelopmentRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.eggDevA * t * t + params.eggDevB * t + params.eggDevC;
        return Math.max(0, val);
    }

    public double larvaDevelopmentRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.larvaDevA * t * t + params.larvaDevB * t + params.larvaDevC;
        return Math.max(0, val);
    }

    public double pupaDevelopmentRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.pupaDevA * t * t + params.pupaDevB * t + params.pupaDevC;
        return Math.max(0, val);
    }

    // ------------------------------------------------------------------------
    // Stage survival probability over the whole stage (0..1) – Gaussian
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
    // Daily mortality rates (1/day) derived from survival and development
    //   μ = -ln(S) * r
    // ------------------------------------------------------------------------
    public double eggMortalityRate(double tempKelvin) {
        double r = eggDevelopmentRate(tempKelvin);
        double S = eggSurvival(tempKelvin);
        if (r <= 0 || S <= 0) return 1.0; // development impossible → die
        double mu = -Math.log(S) * r;
        return Math.min(1.0, Math.max(0.0, mu));
    }

    public double larvaMortalityRate(double tempKelvin) {
        double r = larvaDevelopmentRate(tempKelvin);
        double S = larvaSurvival(tempKelvin);
        if (r <= 0 || S <= 0) return 1.0;
        double mu = -Math.log(S) * r;
        return Math.min(1.0, Math.max(0.0, mu));
    }

    public double pupaMortalityRate(double tempKelvin) {
        double r = pupaDevelopmentRate(tempKelvin);
        double S = pupaSurvival(tempKelvin);
        if (r <= 0 || S <= 0) return 1.0;
        double mu = -Math.log(S) * r;
        return Math.min(1.0, Math.max(0.0, mu));
    }

    // Adult mortality rate (directly from quadratic)
    public double adultMortalityRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double val = params.adultMortA * t * t + params.adultMortB * t + params.adultMortC;
        return Math.min(1.0, Math.max(0.0, val));
    }

    // ------------------------------------------------------------------------
    // Fecundity (eggs/female/day) – temperature‑peaked asymmetric function
    // ------------------------------------------------------------------------
    public double fecundityRate(double tempKelvin) {
        double t = celsius(tempKelvin);
        double term1 = params.fecundityA * Math.exp(params.fecundityB * t);
        double exponent = Math.pow((params.fecundityTmax - t) / params.fecundityC, 2);
        double term2 = Math.exp(params.fecundityB * params.fecundityTmax - exponent);
        return Math.max(0, term1 - term2);
    }

    // Host carrying capacity: K = 1 + log(1 + L), where L is livestock density.
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
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    public double mortalityProb(double ratePerDay) {
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    // ------------------------------------------------------------------------
    // Stage transition logic (now with separate mortality each tick)
    // ------------------------------------------------------------------------
    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
        double dL = larvaDevelopmentRate(tempKelvin);
        double mL = larvaMortalityRate(tempKelvin);
        double pAdv = transitionProb(dL);
        double pDie = mortalityProb(mL);

        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as LARVA at age %d, temp=%.2fK",
                                  agent.getId(), agent.getAge(), tempKelvin);
            return;
        }

        if (rng.nextDouble() < pAdv) {
            agent.setStage(LifecycleStage.PUPA);
            agent.setEnergy(0.6);
            SimulationLogger.info("[SUCCESS] %s LARVA → PUPA at age %d, temp=%.2fK",
                                  agent.getId(), agent.getAge(), tempKelvin);
        }
    }

    public void tryAdvanceFromPupa(LivingAgent agent, double tempKelvin) {
        double dP = pupaDevelopmentRate(tempKelvin);
        double mP = pupaMortalityRate(tempKelvin);
        double pAdv = transitionProb(dP);
        double pDie = mortalityProb(mP);

        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as PUPA at age %d, temp=%.2fK",
                                  agent.getId(), agent.getAge(), tempKelvin);
            return;
        }

        if (rng.nextDouble() < pAdv) {
            agent.setStage(LifecycleStage.ADULT);
            agent.setEnergy(0.9);
            SimulationLogger.info("[SUCCESS] %s PUPA → ADULT at age %d, temp=%.2fK",
                                  agent.getId(), agent.getAge(), tempKelvin);
        }
    }

    public void applyAdultMortality(LivingAgent agent, double tempKelvin) {
        double mA = adultMortalityRate(tempKelvin);
        double pDie = mortalityProb(mA);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as ADULT at age %d, temp=%.2fK",
                                  agent.getId(), agent.getAge(), tempKelvin);
        }
    }

    /**
     * Stochastic egg laying for an adult female.
     * Returns the number of eggs to deposit into a nearby water tank.
     */
    public int eggsToLay(double tempKelvin, double hostDensity, ThreadLocalRandom rng) {
        double expected = effectiveFecundity(tempKelvin, hostDensity);
        int eggs = (int) expected;
        if (rng.nextDouble() < (expected - eggs)) eggs++;
        return Math.min(eggs, 50);
    }

    // ------------------------------------------------------------------------
    // Egg hatching (for InertAgent) – separate mortality & development
    // ------------------------------------------------------------------------
    public int tryHatchEggs(int currentEggs, double tempKelvin, ThreadLocalRandom rng) {
        if (currentEggs == 0) return 0;

        double de = eggDevelopmentRate(tempKelvin);
        double me = eggMortalityRate(tempKelvin);
        double pAdv = transitionProb(de);
        double pDie = mortalityProb(me);

        // 1. Apply mortality
        int deaths;
        if (currentEggs < 100) {
            deaths = 0;
            for (int i = 0; i < currentEggs; i++) if (rng.nextDouble() < pDie) deaths++;
        } else {
            deaths = (int) Math.round(currentEggs * pDie);
        }
        int survivors = currentEggs - deaths;

        // 2. Apply hatching (development) to survivors
        int hatched;
        if (survivors < 100) {
            hatched = 0;
            for (int i = 0; i < survivors; i++) if (rng.nextDouble() < pAdv) hatched++;
        } else {
            hatched = (int) Math.round(survivors * pAdv);
        }
        return hatched;
    }
}