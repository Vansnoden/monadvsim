---

<!-- ## Contact & Citation -->

<!-- For questions or to report issues, please contact [your email / GitHub issues].

If you use this framework in a publication, cite:

> [Your name et al., "MONADSIM: A high‑performance multi‑agent mosquito simulation", Year, Journal/DOI] -->

```


#### CLipped climatic data

```
./clip_timeseries_data \
    --source=/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/climatic_timeseries_data/2020_data.grib \
    --bounds=/home/void/Documents/codes/monadvsim/prepared_data/dire/dire_dawa.shp \
    --target=/home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/climate_t2m_tp_2020.nc \
    --daily \
    --buffer=1.0 \
    --variables=2t,tp \
    --rename-t2m \
    --verbose

```


#### Inspect clipped data:

```cdo sinfo /home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/climate_t2m_tp_2020.nc```

```cdo showvar /home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/climate_t2m_tp_2020.nc```

```ogrinfo -so -al /home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/dire_dawa.shp```

renaming variable:

```cdo chname,2t,t2m,tp,tp climate_t2m_tp_2020.nc climate_t2m_tp_2020.nc```


#### Downscaling large rasters before processing

```
gdal_translate -outsize 10% 10% /mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/somali_building.tif /mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_building.tif
```

```
{
  gdalinfo -stats "/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_building.tif"
  echo "========================================="
  gdalinfo -stats "/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_elevation.tif"
  echo "========================================="
  gdalinfo -stats "/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_population.tif"
} | xclip -selection clipboard
```


#### Merge many geotiff into one

```
# Merge Building Density
gdalbuildvrt Somali_Building_Density_10m.vrt Somali_Building_Density_10m-*.tif
gdal_translate -co COMPRESS=LZW -co BIGTIFF=IF_NEEDED Somali_Building_Density_10m.vrt Somali_Building_Density_10m.tif

# Merge Elevation
gdalbuildvrt Somali_Elevation_10m.vrt Somali_Elevation_10m-*.tif
gdal_translate -co COMPRESS=LZW -co BIGTIFF=IF_SAFER Somali_Elevation_10m.vrt Somali_Elevation_10m.tif

# Merge Population
gdalbuildvrt Somali_Population_10m.vrt Somali_Population_10m-*.tif
gdal_translate -co COMPRESS=LZW -co BIGTIFF=IF_SAFER Somali_Population_10m.vrt Somali_Population_10m.tif
```
