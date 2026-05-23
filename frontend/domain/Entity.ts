import { AnalysisService } from '../application/AnalysisService'

export interface Entity {
  id: string;
  createdAt: Date;
}

export class BaseEntity implements Entity {
  constructor(public readonly id: string, public readonly createdAt: Date) { }

  equals(other: Entity): boolean {
    return this.id === other.id;
  }
}
