package com.example.ts15quarkusbanking.api;

import com.example.ts15quarkusbanking.domain.DomainValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/** Domain rule violations are 422 Unprocessable Entity. */
@Provider
public class DomainValidationExceptionMapper implements ExceptionMapper<DomainValidationException> {
    @Override
    public Response toResponse(DomainValidationException e) {
        return Response.status(422).type(MediaType.APPLICATION_JSON).entity(Map.of("detail", String.valueOf(e.getMessage()))).build();
    }
}
