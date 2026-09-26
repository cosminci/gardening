# Pipeline testing

> Standard: Agentic Engineering Standards v1.2.0

## Strategy

Isolated tests prove affected-component selection and version decisions. Running the actual Dagger pipeline proves that containerized build hooks still work together; a green selection test alone cannot establish that the chosen component's build succeeds.
