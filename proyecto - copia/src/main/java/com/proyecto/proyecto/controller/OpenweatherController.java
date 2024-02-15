package com.proyecto.proyecto.controller;

import java.util.*;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import com.nimbusds.jose.shaded.json.parser.ParseException;
import com.proyecto.proyecto.dto.Mensaje;
import com.proyecto.proyecto.entity.Consulta;
import com.proyecto.proyecto.entity.Geocoding;
import com.proyecto.proyecto.security.dto.*;
import com.proyecto.proyecto.security.entity.*;
import com.proyecto.proyecto.security.enums.RolNombre;
import com.proyecto.proyecto.security.jwt.JwtProvider;
import com.proyecto.proyecto.security.service.*;
import com.proyecto.proyecto.service.ConsultaService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;


@RestController
@RequestMapping("/api")
@Api(tags = "OpenWeather", description = "Endpoints para obtener información meteorológica")
public class OpenweatherController {
    
    private final String apiKey = "1dc3929bb99168e29431b2ba08de4fb6";

    RestTemplate restTemplate = new RestTemplate();

    @Autowired
    ConsultaService consultaService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    UsuarioService usuarioService;

    @Autowired
    RolService rolService;

    @Autowired
    JwtProvider jwtProvider;


    //Inicio EndPoint para registrar los usuarios
    @PostMapping("/register")
    @ApiOperation(value = "Registrar un usuario", notes = "Registra un usuario en la base de datos")
    public ResponseEntity<Mensaje> nuevo(@Valid @RequestBody NuevoUsuario nuevoUsuario, BindingResult bindingResult) {
        if (bindingResult.hasErrors())
            return new ResponseEntity<Mensaje>(new Mensaje("Verifique los datos introducidos"), HttpStatus.BAD_REQUEST);

        if (usuarioService.existsByNombreUsuario(nuevoUsuario.getNombreUsuario()))
            return new ResponseEntity<Mensaje>(new Mensaje("El nombre " + nuevoUsuario.getNombre() + " ya se encuentra registrado"), HttpStatus.BAD_REQUEST);

        if (usuarioService.existsByEmail(nuevoUsuario.getEmail()))
            return new ResponseEntity<Mensaje>(new Mensaje("El email " + nuevoUsuario.getEmail() + " ya se encuentra registrado"), HttpStatus.BAD_REQUEST);

        Usuario usuario = new Usuario(nuevoUsuario.getNombre(), nuevoUsuario.getNombreUsuario(), nuevoUsuario.getEmail(), passwordEncoder.encode(nuevoUsuario.getPassword()));
        Set<Rol> roles = new HashSet<>();
        roles.add(rolService.getByRolNombre(RolNombre.ROLE_USER).get());

        if (nuevoUsuario.getRoles().contains("admin"))
            roles.add(rolService.getByRolNombre(RolNombre.ROLE_ADMIN).get());

        usuario.setRoles(roles);
        usuarioService.save(usuario);
        return new ResponseEntity<Mensaje>(new Mensaje("Usuario registrado con éxito"), HttpStatus.CREATED);
    } //Fin EndPoint para registrar los usuarios



    //Inicio EndPoint para autenticar usuarios
    @PostMapping("/login")
    @ApiOperation(value = "Autenticacion un usuario", notes = "valida la existencia de un usuario y devulve un token")
    public ResponseEntity<?> login(@Valid @RequestBody LoginUsuario loginUsuario, BindingResult bindingResult) {
        if (bindingResult.hasErrors())
            return new ResponseEntity<Mensaje>(new Mensaje("Usuario inválido"), HttpStatus.UNAUTHORIZED);
    
        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginUsuario.getNombreUsuario(), loginUsuario.getPassword()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtProvider.generateToken(authentication);
        JwtDto jwtDto = new JwtDto(jwt);
        return new ResponseEntity<JwtDto>(jwtDto, HttpStatus.ACCEPTED);
    }//Fin EndPoint para autenticar usuarios



    //Inicio EndPoint para actualizar token
    @PostMapping("/refresh")
    public ResponseEntity<JwtDto> refresh(@RequestBody JwtDto jwtDto) throws ParseException, java.text.ParseException {
        String token = jwtProvider.refreshToken(jwtDto);
        JwtDto jwt = new JwtDto(token);
        return new ResponseEntity<JwtDto>(jwt, HttpStatus.OK);
    }//Fin EndPoint para refrescar token



    //Inicio EndPoint para obtener clima actual de una ciudad
    @GetMapping("/weather")
    @Cacheable(value = "weatherCache", key = "#cityName")
    @ApiOperation(value = "Obtener clima actual por ciudad", notes = "Obtiene el clima actual por ciudad utilizando la API de OpenWeatherMap")
    public String getWeather(@ApiParam(value = "Nombre de la ciudad", required = true) @RequestParam String cityName) {

        String apiUrl = "https://api.openweathermap.org/data/2.5/weather?q=" + cityName + "&appid=" + apiKey;

        RestTemplate restTemplate = new RestTemplate();
        String response = restTemplate.getForObject(apiUrl, String.class);

        consultaService.guardarConsulta(cityName,"Weather",response);

        return response;
    }//Fin EndPoint para obtener clima actual de una ciudad



    //Inicio EndPoint para obtener el pronostico del tiempo para 5 dias con datos cada 3 horas
    @GetMapping("/forecast")
    @Cacheable(value = "forecastCache", key = "#cityName")
    @ApiOperation(value = "Obtener pronóstico del tiempo", notes = "Obtiene el pronóstico del tiempo para los próximos días utilizando la API de OpenWeatherMap")
    public String getForecast(@ApiParam(value = "Nombre de la ciudad", required = true) @RequestParam String cityName) {
        // No hay verificación de autenticación aquí

        String apiUrl = "https://api.openweathermap.org/data/2.5/forecast?q=" + cityName + "&appid=" + apiKey;
        String response = restTemplate.getForObject(apiUrl, String.class);

        consultaService.guardarConsulta(cityName,"Forecast",response);

        return response;
    }//Fin EndPoint para obtener el pronostico del tiempo para 5 dias con datos cada 3 horas



    //Inicio EndPoint para obtener la contaminacion del aire
    @GetMapping("/airpolution")
    @Cacheable(value = "airPollutionCache", key = "#cityName")
    @ApiOperation(value = "Obtener contaminación del aire", notes = "Obtiene la información sobre la contaminación del aire utilizando la API de OpenWeatherMap")
    public String getAirPollutionForecast(@ApiParam(value = "Nombre de la ciudad", required = true) @RequestParam String cityName) {
        // No hay verificación de autenticación aquí

        String geoApiUrl = "http://api.openweathermap.org/geo/1.0/direct?q=" + cityName + "&appid=" + apiKey;
        Geocoding[] geocodings = restTemplate.getForObject(geoApiUrl, Geocoding[].class);

        if (geocodings != null && geocodings.length > 0) {

            Geocoding geocoding = geocodings[0];
            double lat = geocoding.getLat();
            double lon = geocoding.getLon();

            String airPollutionApiUrl = "http://api.openweathermap.org/data/2.5/air_pollution/forecast?lat=" + lat + "&lon=" + lon + "&appid=" + apiKey;
            String response = restTemplate.getForObject(airPollutionApiUrl, String.class);

            consultaService.guardarConsulta(cityName,"AirPolution",response);


            return response;
        } else {
            return "No se encontraron coordenadas para la ciudad: " + cityName;
        }
    }//Fin EndPoint para obtener la contaminacion del aire


}
