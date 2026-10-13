package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.LifecycleStage;
import com.monadvsim.app.models.entities.LivingAgent;
import com.monadvsim.app.models.utils.SeedManager;
import com.monadvsim.app.models.utils.SimulationLogger;
import java.util.Random;

/**
 * Temperature‑dependent life‑cycle model for Anopheles stephensi
 * following the ODE differential equations of Komi et al. 2025.
 */
public class LifecycleModel {

    private final SpeciesParameters p;
    private final double dtDays;          
    private final Random rng;  
    
    private static final double T_BASE_LARVA = 13.3;        // Base temperature in Celsius
    private static final double T_BASE_PUPA = 10.0;         // Base temperature for pupa
    private static final double DEGREE_DAYS_LARVA_TO_PUPA = 66.5;  // Required thermal accumulation
    private static final double DEGREE_DAYS_PUPA_TO_ADULT = 22.0;   // Required for pupa to adult
    private static final double DEGREE_DAYS_TO_PUPA = 66.5; // Required thermal accumulation
    

    public LifecycleModel(SpeciesParameters params, double tickMinutes) {
        this.p = params;
        this.dtDays = tickMinutes / (24.0 * 60.0);
        this.rng = SeedManager.getRandom(); 
        SimulationLogger.info("[Lifecycle] dtDays = %.6f days (tickMinutes = %.1f)", dtDays, tickMinutes);
    }

    private double celsius(double kelvin) { return kelvin - 273.15; }

    public double eggDevelopmentRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        double expPart = Math.exp(p.eggDev_rho * T
                - Math.exp(p.eggDev_rho * p.eggDev_k - (p.eggDev_k - T) / p.eggDev_Delta));
        return Math.max(0, expPart + p.eggDev_lambda);
    }

    public double larvaDevelopmentRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        if (T <= p.larvaDev_Tmin || T >= p.larvaDev_Tmax) return 0.0;
        double val = p.larvaDev_a * T * (T - p.larvaDev_Tmin)
                * Math.pow(p.larvaDev_Tmax - T, 1.0 / p.larvaDev_m);
        return Math.max(0, val);
    }

    public double pupaDevelopmentRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        double expPart = Math.exp(p.pupaDev_rho * T
                - Math.exp(p.pupaDev_rho * p.pupaDev_k - (p.pupaDev_k - T) / p.pupaDev_Delta));
        return Math.max(0, expPart + p.pupaDev_lambda);
    }

    public double eggMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        return Math.exp(p.eggMort_b1 + p.eggMort_b2 * T + p.eggMort_b3 * T * T);
    }

    public double larvaMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        return Math.exp(p.larvaMort_b1 + p.larvaMort_b2 * T + p.larvaMort_b3 * T * T);
    }

    public double pupaMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        return Math.exp(p.pupaMort_b1 + p.pupaMort_b2 * T + p.pupaMort_b3 * T * T);
    }

    public double adultMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        if (p.adultMort_b1 != 0 || p.adultMort_b2 != 0 || p.adultMort_b3 != 0) {
            return Math.exp(p.adultMort_b1 + p.adultMort_b2 * T + p.adultMort_b3 * T * T);
        }
        return p.adultMortalityPerDay;
    }

    
    /**
    * Raw temperature response of fecundity, in "eggs per female per day"
    * BEFORE sex ratio and host-density scaling. The Gaussian-on-log form
    * follows Komi et al. 2025.
    */
    public double fecundityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        double exponent = p.fecundity_rmax + p.fecundity_c * Math.pow(p.fecundity_Topt - T, 2);
        return p.fecundityScale * Math.max(0.0, Math.exp(exponent));
    }


    /**
    * Density-dependent host-availability multiplier on fecundity.
    * Uses log1p so an empty landscape still has a small non-zero factor.
    */
    public double hostCarryingCapacity(double hostDensity) {
        double host = Math.max(0.0, hostDensity);
        return p.hostCarryingCapacityBase + Math.log1p(host);
    }

    
    /**
    * Expected eggs per female per tick. This is the quantity the Poisson
    * sampler consumes; it is intentionally the full product of every
    * dimensioned factor so the calibration knob ({@link SpeciesParameters#fecundityScale})
    * has a single, predictable effect.
    */
    public double effectiveFecundity(double tempKelvin, double hostDensity) {
        return fecundityRate(tempKelvin)
             * p.sexRatio
             * hostCarryingCapacity(hostDensity)
             * dtDays;
    }

    
    public double transitionProb(double ratePerDay) {
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    
    public double mortalityProb(double ratePerDay) {
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    
    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
        double tempC = celsius(tempKelvin);
        double mL = larvaMortalityRate(tempKelvin);

        // 1. Accumulate degree-days
        if (tempC > T_BASE_LARVA) {
            double dd = (tempC - T_BASE_LARVA) * dtDays;
            double newDD = agent.getAccumulatedDegreeDays() + dd;
            agent.setAccumulatedDegreeDays(newDD);
        }

        // 2. Check if enough degree-days accumulated to pupate
        if (agent.getAccumulatedDegreeDays() >= DEGREE_DAYS_LARVA_TO_PUPA) {
            agent.setStage(LifecycleStage.PUPA);
            agent.setAccumulatedDegreeDays(0.0);
            agent.setStageAgeDays(0.0);
            agent.setStageAgeTicks(0); // <-- CRITICAL: Reset stage age
            agent.setDevelopmentProgress(0.0);
            agent.setEnergy(0.6);
            // DO NOT reset age here - it's for total lifespan tracking

            SimulationLogger.info("[SUCCESS] %s LARVA → PUPA at stage age %d, DD=%.2f, temp=%.2fC",
                agent.getId().substring(0, 8), 
                agent.getStageAgeTicks(), // Will now show 0
                DEGREE_DAYS_LARVA_TO_PUPA, 
                tempC);
            return;
        }

        // 3. Apply mortality
        double pDie = mortalityProb(mL);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as LARVA at stage age %d, temp=%.2fC",
                agent.getId().substring(0, 8), 
                agent.getStageAgeTicks(), 
                tempC);
        }
    }


    public void tryAdvanceFromPupa(LivingAgent agent, double tempKelvin) {
        double tempC = celsius(tempKelvin);
        double mP = pupaMortalityRate(tempKelvin);

        // 1. Accumulate degree-days for pupa
        if (tempC > T_BASE_PUPA) {
            double dd = (tempC - T_BASE_PUPA) * dtDays;
            double newDD = agent.getAccumulatedDegreeDays() + dd;
            agent.setAccumulatedDegreeDays(newDD);
        }

        // 2. Check if enough degree-days to emerge
        if (agent.getAccumulatedDegreeDays() >= DEGREE_DAYS_PUPA_TO_ADULT) {
            agent.setStage(LifecycleStage.ADULT);
            agent.setAccumulatedDegreeDays(0.0);
            agent.setStageAgeDays(0.0);
            agent.setStageAgeTicks(0); // <-- CRITICAL: Reset stage age
            agent.setEnergy(0.9);
            // DO NOT reset age here - it's for total lifespan tracking

            SimulationLogger.info("[SUCCESS] %s PUPA → ADULT at stage age %d, DD=%.2f, temp=%.2fC",
                agent.getId().substring(0, 8), 
                agent.getStageAgeTicks(), // Will now show 0
                DEGREE_DAYS_PUPA_TO_ADULT, 
                tempC);
            return;
        }

        // 3. Apply mortality
        double pDie = mortalityProb(mP);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as PUPA at stage age %d, temp=%.2fC",
                agent.getId().substring(0, 8), 
                agent.getStageAgeTicks(), 
                tempC);
        }
    }
    
    
    public void applyAdultMortality(LivingAgent agent, double tempKelvin) {
        double mA = adultMortalityRate(tempKelvin);
        double pDie = mortalityProb(mA);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as ADULT at age %d, temp=%.2fK",
                    agent.getId(), agent.getAge(), celsius(tempKelvin));
        }
    }

    
    public int tryHatchEggs(int currentEggs, double tempKelvin) {
        if (currentEggs <= 0) return 0;
        double de = eggDevelopmentRate(tempKelvin);
        double me = eggMortalityRate(tempKelvin);
        double pAdv = transitionProb(de);
        double pDie = mortalityProb(me);

        int deaths;
        if (currentEggs < 100) {
            deaths = 0;
            for (int i = 0; i < currentEggs; i++) {
                if (rng.nextDouble() < pDie) deaths++;
            }
        } else {
            deaths = (int) Math.round(currentEggs * pDie);
        }
        int survivors = currentEggs - deaths;

        int hatched;
        if (survivors < 100) {
            hatched = 0;
            for (int i = 0; i < survivors; i++) {
                if (rng.nextDouble() < pAdv) hatched++;
            }
        } else {
            hatched = (int) Math.round(survivors * pAdv);
        }
        return hatched;
    }

    
    /**
    * Poisson sample of the expected egg count per tick, capped at the
    * biological maximum per laying event.
    *
    * <p>Knuth's algorithm is exact for λ ≲ 30 (which covers our range)
    * and O(λ) — negligible next to everything else the tick does.
    * Falls back to a normal approximation above that.
    */
    public int eggsToLay(double tempKelvin, double hostDensity) {
        double lambda = effectiveFecundity(tempKelvin, hostDensity);
        if (lambda <= 0.0) return 0;

        int eggs = poisson(lambda);

        // Cap at a biologically defensible single-lay maximum.
        return Math.min(eggs, 50);
    }
    
    
    /**
    * Poisson sample using Knuth's algorithm (λ < 30) or a rounded normal
    * approximation (λ ≥ 30). Uses the shared LifecycleModel RNG so the
    * global seed still governs repeatability.
    */
    private int poisson(double lambda) {
        if (lambda >= 30.0) {
            // Normal approximation is fine here; integrality is preserved
            // by rounding. stddev = sqrt(lambda).
            double g = rng.nextGaussian() * Math.sqrt(lambda) + lambda;
            return Math.max(0, (int) Math.round(g));
        }
        double L = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > L);
        return k - 1;
    }

}